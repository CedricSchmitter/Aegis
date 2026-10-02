# ADR-0025: Household assistant chat with checked citations

Status: accepted (first part of issue #25 implemented). Date: 2026-10-02.

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
- `read_document`: the archived text through the new `DocumentTextQuery` contract, in parts of about 12,000
  characters framed as untrusted content, with page markers where the archive separates pages by form feeds, plus
  title, folder, labels, document date and the contracts that cite the document.
- `list_contracts`, `read_contract`: contracts with every fact, its review state, validity period, source document,
  page and quote, contradictions and the cancellation deadline with its rule.
- `list_tasks`: tasks with due date and planned day kept apart, the source document and cancellation reviews.

The system prompt tells the model to narrow with labels but to search with words as well when a filtered search finds
little, and to say when documents may have been missed, because labels can be wrong or missing; to compare an original
contract with its amendments by date; to cite every statement; to name missing, contradictory or outdated evidence;
and to answer in the language of the question (the member's locale as fallback). A request to change something is
answered with where the member can do it; the chat has no writing tool.

### Checked citations

The answer is JSON: text with markers such as `[2]`, an evidence level (`COMPLETE`, `PARTIAL`, `NOT_FOUND`), up to 12
citations (kind, id, quote) and up to 8 caveats. Before it is stored, Aegis checks every citation against its source:
a document quote must occur in the document's archived text, ignoring case, line breaks, words hyphenated across
lines, typographic quotes and dashes, with `...` skipping text; the page is counted from the archive's page breaks. A
contract or task must exist and not be in the trash. A citation that fails is kept and marked `NOT_IN_SOURCE` or
`SOURCE_UNAVAILABLE`, so the member sees which statement lacks support instead of losing the hint that it was made.

### Tool loop on the subscription

ADR-0003 is amended: the `claude-code` provider expresses tool calls and conversations in its structured answer. A
request with tools or with more than one message is sent as one stateless CLI run whose prompt is the conversation
as a JSON array (user messages, earlier answers, tool calls, tool results) and whose JSON schema is
`{toolCalls: [{name, input}], answer: <workload schema> | null}`. Aegis turns requested calls into ordinary tool calls
of the run, so grants, refusals, budgets, audit steps and the executor are identical to the API provider; the CLI still
runs without any tool, and a single prompt without tools is sent unchanged as before. JSON keeps content inside a tool
result from closing its frame and posing as another role. The subscription still serves only its owner's runs.

### Delivery

The web app polls an open question every 1.5 seconds and shows the run's steps as progress ("searching documents",
"reading a document"); leaving and returning shows the same state. Answers are not streamed token by token: the
subscription has no streaming, and the progress already shows what happens.

## Consequences

- Questions work on the owner's subscription today and for both members once an API key is installed; the chat page
  shows why it is unavailable otherwise, while documents, search and tasks never depend on it.
- Every model call resends the conversation, so long research costs more input tokens on the subscription than on the
  API; the chat run budget (0.10 USD on the API, 0.50 USD per run on the subscription) and the tool and model call
  limits bound it.
- Quotes are checked for presence, not for whether they support the sentence that cites them; the evidence level and
  caveats remain the model's assessment.
- Conversations are not part of the portable export; they are personal working notes in the database backup.
  Permanently deleting a document does not remove quotes from earlier answers; deleting the conversation does.
- Not yet done in #25: writing actions with concrete authorization, token streaming, a fixed set of reference
  questions with expected sources, and the evaluation of labels plus full text against full text alone.
