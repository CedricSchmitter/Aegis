# ADR-0025: Household assistant chat with checked citations

Status: accepted (first part of issue #25 implemented, reference questions evaluated). Date: 2026-10-02.

## Context

Issue #25 asks for questions such as "When does contract X expire?" answered from the household's own documents,
contract facts and tasks, with sources, visible progress, persisted conversations, cancellation and bounded research,
read-only by default. ADR-0003 provides the bounded run model, the `chat` workload profile and the rule that tools are
read-only adapters over public application contracts with the requesting member's actor. Its subscription provider
(`claude-code`) accepted only one prompt without tools, while the household currently has no API key, so a chat that
needs a tool loop could not run at all.

A probe on 2026-10-02 through the real host runner (fixed flags, no built-in tools, synthetic data) showed that
headless `claude -p` can drive a tool loop when tool calls are part of its structured answer: three calls (search, read
two documents, answer) took about 12 seconds and 0.03 USD API-equivalent with Sonnet 5.5, followed the schema every
time, quoted verbatim and ignored an instruction embedded in a document.

## Decision

### Conversations

A conversation (`assistant` context, tables `chat_conversations` and `chat_turns`) belongs to the member who started
it; only that member lists, reads, continues or deletes it, and other members get "not found". It holds up to 100
questions in order; one question is open at a time, so each answer can build on the earlier ones. The client chooses
the conversation and question ids, which makes asking idempotent: repeating a request after a lost response returns
what the first one created, and the same id with different words is refused as `chat.idConflict`.

Each question starts one `CHAT` run (idempotency key `chat:<question id>`) in the same transaction that stores the
question, so a question never exists without its run. The run's state is the truth about how far the answer is
(`WAITING`, `WORKING`, `FAILED`, `CANCELLED`); the conversation only receives the finished answer. Cancelling stops the
run at its next checkpoint and nothing is stored; a failed or cancelled latest question can be asked again with a new
run that references the old one. Deleting a conversation cancels an open question first. Runs stay as audit data.

Every member sees every run (ADR-0003), so a chat run stores only a content-free summary (question id, evidence, number
of citations and how many were verified); questions, answers and quotes live in the member's conversation. The
answer is written by the workload handler in the run's completion transaction under the effect key
`chat:<question id>`, so a revived or repeated completion cannot store it twice, and only the question's current run
can answer it.

### Research tools

The `chat` workload is granted six read-only tools, each calling public application contracts with the run's actor:

- `household_catalog`: folders with paths and active labels by group, so searches can use ids.
- `search_documents`: the documents context's structured search (`DocumentSearchRequest`) with words, folder scope,
  label groups and ALL for topics and tags. It therefore matches exactly like the web app: AND across groups, OR
  within a group, explicit ALL, words in title and archived full text. It returns metadata only.
- `read_document`: the document's text through the `DocumentTextQuery` contract (see "Text per page"), in parts of
  about 12,000 characters framed as untrusted content, with a marker such as `--- page 2 ---` before every page when
  the pages are known, plus title, folder, labels, document date and the contracts that cite the document.
- `list_contracts`, `read_contract`: contracts with every fact, its review state, validity period, source document,
  page and quote, contradictions and the cancellation deadline with its rule.
- `list_tasks`: tasks with due date and planned day kept apart, the source document and cancellation reviews.

The system prompt tells the model to narrow with labels but to search with words as well when a filtered search finds
little, and to say when documents may have been missed, because labels can be wrong or missing; to read a result that
matches its search words even when its title or labels do not seem to fit, above all for questions about every
document of a kind (see "Reference questions"); to compare an original
contract with its amendments by date; to cite every statement; to name missing, contradictory or outdated evidence;
and to answer in the language of the question (the member's locale as fallback). A request to change something is
answered with where the member can do it; the chat has no writing tool.

`AEGIS_AI_CHAT_RETRIEVAL` (`aegis.ai.chat-retrieval`) selects the retrieval. `LABELS_AND_TEXT`, the default, is the
behavior above. `TEXT_ONLY` exists to compare retrieval with the reference questions: it withholds
`household_catalog` and grants `search_document_text` instead of `search_documents`, the same structured search
without folder and label filters. Results still show a document's folder and labels.

### Search coverage

Both retrieval modes pass `contentSearch`, `searchLimitation`, `archiveTotal`, `countAccuracy`, `notContentIndexed`,
Aegis paging and `omittedDocuments` to the model. Archive traversal is bounded as described in ADR-0002; a complete
archive query is not proof of semantic or household-wide recall. Search results are sized before serialization:
whole metadata items are omitted as needed to keep valid JSON below the executor's existing 20,000-character
boundary, with an omission count. Ordinary Aegis pages can be requested separately; partial archive traversal
cannot be repaired by paging Aegis results alone.

The prompt and tool descriptions ask the model to narrow or retry incomplete retrieval and to explain missing
sources. A focused, provider-independent guard in the existing executor carries an attempt-local coverage flag
from search tools to answer validation (not document bodies or a persistent passage observation ledger). Any
limited/unavailable search, applicable unindexed source, failed search tool or omitted metadata forces `PARTIAL`
and a German/English caveat. A proposed `NOT_FOUND` answer is replaced by a statement that the retrieved sources
did not establish an answer; it cannot prove absence. Later successful searches in the same attempt do not erase
an earlier technical gap. This guard does not check the semantic support of prose or inspected-passage provenance.
Normal CI uses controlled archive responses and a scripted extension of the synthetic reference harness; no model
calls or household documents are needed.

### Checked citations

The answer is JSON: text with markers such as `[2]`, an evidence level (`COMPLETE`, `PARTIAL`, `NOT_FOUND`), up to 12
citations (kind, id, quote) and up to 8 caveats. Before it is stored, Aegis checks every citation against its source:
a document quote must occur in the document's text, ignoring case, line breaks, words hyphenated across lines and
pages, typographic quotes and dashes, with `...` skipping text; the citation's page is the page on which the quote
starts, when the document's pages are known. A contract or task must exist and not be in the trash. A citation that
fails is kept and marked `NOT_IN_SOURCE` or `SOURCE_UNAVAILABLE`, so the member sees which statement lacks support
instead of losing the hint that it was made.

### Text per page

The OCR text Paperless delivers carries no page breaks, even for a two-page PDF (live check on 2026-10-02), so the
documents context reads the text page by page from the PDF itself. `DocumentTextQuery` returns the pages in order
together with whether they are known; positions in the joined text name their page. The text comes from the text
layer of:

1. the archive's searchable PDF, which OCR generates for photos and scans, or otherwise
2. a PDF original, as for a born-digital PDF of which the archive keeps no archive version: the staging copy while it
   is present, else the archive's original, each only when it matches the original's checksum.

Apache PDFBox reads the text layer behind the documents port `PdfText` in `documents.adapters.pdf` (ADR-0024), ordered
by position on the page like the archive's own extraction. The archive's OCR text without pages remains the fallback
for a photo without an archive version, a PDF that needs a password, cannot be parsed or has no text at all, and a PDF
of more than 1,000 pages or 2,000,000 characters. The assistant uses only the public contract.

One answer reads the same document several times (in parts, then to check its quotes), so the extracted texts of
recently read documents are kept in memory, at most 8,000,000 characters in total, keyed by the document, its archive
id and the original's checksum. They are derived data: never stored, exported or logged, and read again after a
restart. An unreachable archive is reported and not remembered.

### Tool loop on the subscription

ADR-0003 is amended: the `claude-code` provider expresses tool calls and conversations in its structured answer. A
request with tools or with more than one message is sent as one stateless CLI run whose prompt is the conversation
as a JSON array (user messages, earlier answers, tool calls, tool results) and whose JSON schema is
`{toolCalls: [{name, input}], answer: <workload schema> | null}`. Aegis turns requested calls into ordinary tool calls
of the run, so grants, refusals, budgets, audit steps and the executor are identical to the API provider; the CLI still
runs without any tool, and a single prompt without tools is sent unchanged as before. The protocol tells the model
that its own environment offers no tools and that toolCalls always works, because in 2 of 80 evaluation runs the model
answered that the tools were unavailable without having requested any through toolCalls, apparently after trying to
call them directly in the CLI, and in one more the CLI stopped without an answer. JSON keeps content inside a tool
result from closing its frame and posing as another role. The subscription still serves only its owner's runs.

### Delivery

The web app polls an open question every 1.5 seconds and shows the run's steps as progress ("searching documents",
"reading a document"); leaving and returning shows the same state. Answers are not streamed token by token: the
subscription has no streaming, and the progress already shows what happens.

Earlier conversations are hidden in a side panel ("Verlauf") opened from the chat page, so the page starts with the
question field. The panel lists the 50 most recently updated conversations and has a search over all of the member's
conversations (`GET /api/chat/conversations/search?q=`): every word, ignoring case, must occur in the title, a
question or an answer text of the same conversation, possibly in different questions. Words are matched as plain text
(no wildcards), at most eight words and 200 characters; other members' conversations are never found.

### Reference questions

`fixtures/assistant-eval/reference-set.json` holds twelve synthetic documents of a fictional household (contracts,
an amendment, a termination, invoices, a distractor, one deliberately mislabeled document with a meaningless title
and one unsorted document without labels) and ten questions with expected and acceptable sources, expected facts and
evidence levels. The questions name only fictional organizations, so a real collection cannot answer them. Two
harnesses ask them and share the scoring of `ops/eval/chat_reference_check.py report`:

- `ChatReferenceLiveTest` (opt-in, `AEGIS_CHAT_EVAL=true`) runs the real handler, tools, run executor and `AiRun`
  budgets in a household that holds only the reference documents, and records which documents the searches returned
  and the model read. Its word search approximates the archive's (every word, ignoring case and accents).
- `ops/eval/chat_reference_check.py` uploads and files the documents on a running instance as `aegis-dev`, probes the
  application's own search without AI, asks every question in a new conversation and cleans up through the trash.

A run passes when every expected source is cited with a verified quote, the expected facts appear in the answer, the
evidence level is one of the expected ones and no other document is cited. Distractors and acceptable sources may be
cited, for example to contrast them with the answer.

Results on 2026-10-02 with Sonnet 5.5 on the owner's subscription, two repetitions per question and retrieval; the
live runs used VM 102 with the household's real collection besides the reference documents:

| Harness, retrieval | Passed | Source recall | Median latency | Model / tool calls | Input tokens | Cost (API-eq.) |
|---|---|---|---|---|---|---|
| in-process, labels and text | 16/20 | 0.90 | 16.4 s | 3.2 / 4.4 | 38,127 | 0.037 USD |
| in-process, text only | 18/20 | 0.95 | 16.7 s | 3.2 / 4.5 | 33,137 | 0.036 USD |
| live, labels and text | 15/20 | 0.90 | 21.2 s | 3.2 / 4.7 | 40,020 | 0.041 USD |
| live, text only | 16/20 | 0.90 | 22.0 s | 3.1 / 4.2 | 31,145 | 0.035 USD |
| live, labels and text, read-despite-labels prompt | 17/18 | 1.00 | 22.0 s | 3.2 / 5.1 | 46,563 | 0.045 USD |

Input tokens include the provider's cached input, which runs now report (`cacheReadTokens`, `cacheWriteTokens`); on
the subscription it is nearly all of the input.

- Apart from the failures listed last, neither retrieval found a source that the other missed, at the same latency
  and number of calls. The
  model called `household_catalog` in only 2 of 20 runs per harness; the label-capable search definition, sent with
  every call, made the input 15 to 28 percent larger. The set is small and its organization names make word search
  easy, so this shows no advantage of labels here, not that labels never help.
- The application's search without AI excludes the mislabeled document when filtering by its organization or by type
  and topic, and finds it by words ("Falkenried", "MU-ST 123", "Kfz Versicherung"); the unsorted document is found by
  words and under Unsorted only. Asked about the mislabeled document directly, the assistant found it in 7 of 8
  runs of both retrievals before the prompt change; the miss was a subscription protocol failure.
- Asked for every policy of the insurer, the assistant missed the mislabeled one in 6 of 8 runs of both retrievals
  while reporting complete evidence. In the in-process runs, which record search results, the searches returned it
  every time, but the model did not read a result whose title and labels did not fit. With the read-despite-labels instruction it was found in 5 of 5 runs and every expected source
  was cited in all 18 verification runs, at about 16 percent more input.
- Other failures: 3 of 80 runs on the subscription answered without research or stopped without an answer (see "Tool
  loop on the subscription"); one English question was answered in German; on the live instance, a question about a
  fictional provider was answered with "not found" plus a real household document of the same kind, rated PARTIAL.
  One live text-only run showed a stored answer as missing because the conversation view reads the conversation
  before the run (its rerun passed); making that read consistent is a separate fix.

The default stays `LABELS_AND_TEXT`: the evaluation shows no retrieval advantage of either, and the instruction to
read results despite their labels removed the misses that labels and titles caused.

## Consequences

- Questions work on the owner's subscription today and for both members once an API key is installed; the chat page
  shows why it is unavailable otherwise, while documents, search and tasks never depend on it.
- Every model call resends the conversation, so long research costs more input tokens on the subscription than on the
  API; the chat run budget (0.10 USD on the API, 0.50 USD per run on the subscription) and the tool and model call
  limits bound it.
- Document citations name the page on which the quote starts. A document read from the archive's OCR text without
  pages (see "Text per page") is still cited without a page.
- The assistant reads and quotes the PDF's text layer, while full-text search uses the archive's index of its OCR
  text. Both come from the same PDF and differ at most in line order and spacing, which the quote check ignores.
- Quotes are checked for presence, not for whether they support the sentence that cites them; the evidence level and
  caveats remain the model's assessment, except that known technical retrieval gaps force `PARTIAL` and a caveat.
- Conversations are not part of the portable export; they are personal working notes in the database backup.
  Permanently deleting a document does not remove quotes from earlier answers; deleting the conversation does.
- Not yet done in #25: writing actions with concrete authorization and token streaming. The reference questions
  cost about 0.04 USD API-equivalent each; rerun them after changing the prompt, tools or model.
