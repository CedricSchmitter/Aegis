package dev.aegis.assistant.application.chat;

import dev.aegis.assistant.application.InvalidModelOutputException;
import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.PreparedRun;
import dev.aegis.assistant.application.RunContext;
import dev.aegis.assistant.application.ValidatedOutput;
import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.chat.ChatAnswer;
import dev.aegis.assistant.domain.chat.ChatAnswer.Check;
import dev.aegis.assistant.domain.chat.ChatAnswer.Citation;
import dev.aegis.assistant.domain.chat.ChatAnswer.Evidence;
import dev.aegis.assistant.domain.chat.ChatAnswer.SourceKind;
import dev.aegis.assistant.domain.chat.ChatTurn;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.api.TaskSummary;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.TaskId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Answers one question of a conversation from the household's records (issue #25). The model researches with the
 * granted read-only tools and answers with numbered citations. Before the answer is stored, every citation is checked
 * against its source: a document quote must occur in the document's archived text, a contract or task must exist.
 * A citation that fails the check is kept and marked, so the member sees which statement lacks support.
 *
 * <p>The answer belongs to the conversation of the member who asked; the run itself stores only a content-free
 * summary, because every member can see every run.
 */
public class ChatHandler implements WorkloadHandler {
    public static final String CONVERSATION = "conversation";
    public static final String TURN = "turn";
    static final int HISTORY_TURNS = 6;
    static final int HISTORY_CHARS = 16_000;

    private static final String SYSTEM = """
            You are the household assistant of Aegis, the shared workspace in which one household keeps its \
            documents (letters, contracts, invoices, insurance papers and other records), contract facts and tasks. \
            A member of the household asks you a question. Answer it from the household's own records, which you \
            read through the tools of this request. You only read; you cannot change anything.

            Today is %s (%s) in the household's time zone %s.

            How to research:
            - household_catalog lists the folders and labels. Use them to narrow a search, but labels and folders \
            can be missing or wrong: when a filtered search finds nothing useful, search again with words only or a \
            broader filter, and say in a caveat when documents may have been missed.
            - search_documents finds documents by words in their title and archived text and by folder and labels. \
            It returns metadata only. Read a document with read_document before you rely on it; long documents come \
            in parts, so read further parts when the answer may be there.
            - list_contracts and read_contract show the contracts with their facts. Each fact names the document, \
            page and quote it comes from and whether a member confirmed it; the cancellation deadline is calculated \
            from confirmed facts only. Prefer confirmed facts, and check them in the documents when they matter.
            - list_tasks shows the household's tasks with their due dates (the contractual deadline) and planned \
            work days, which are different things.
            - Compare an original contract with its amendments, price changes and terminations: a later statement \
            applies from its own start date, and the original stays valid for everything it does not change. Say \
            which document states what, and point out statements that contradict each other or may be outdated.
            - Your number of tool calls is limited. Stop when you have enough evidence or more searching is \
            unlikely to help.

            How to answer:
            - Answer in the language of the question; if that is unclear, in %s. Be concise and concrete: dates, \
            amounts, names and deadlines. Write plain text without Markdown: short paragraphs, and lines that \
            start with "- " for lists.
            - Support every statement about the household's records with a citation. Number the citations in the \
            order of the citations array and refer to them in the text as [1], [2] and so on.
            - A document citation quotes the words exactly as read_document returned them: a short passage of at \
            most 300 characters; mark left-out words with "...". A contract or task citation may name the fact you \
            used in its quote, or leave the quote empty. Cite only ids that a tool returned in this conversation.
            - evidence is COMPLETE when the records answer the question, PARTIAL when they answer part of it or \
            leave doubt, and NOT_FOUND when they do not answer it.
            - caveats name missing, contradictory or outdated evidence, documents you could not read and searches \
            that may have missed documents. Leave the list empty when there is nothing to add.
            - Never invent facts, dates or amounts. A calculation, such as a cancellation date from the start, \
            term and notice period, is welcome when you cite its inputs and say that it is calculated.
            - When the member asks you to change, create, complete or delete something, explain that you can only \
            read, and name the page of the app where they can do it themselves.""";

    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "answer", Map.of("type", "string",
                            "description", "The answer for the member, with citation markers such as [1]"),
                    "evidence", Map.of("type", "string", "enum", List.of("COMPLETE", "PARTIAL", "NOT_FOUND")),
                    "citations", Map.of("type", "array", "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "kind", Map.of("type", "string", "enum", List.of("DOCUMENT", "CONTRACT", "TASK")),
                                    "id", Map.of("type", "string", "description", "The id a tool returned"),
                                    "quote", Map.of("type", "string")),
                            "required", List.of("kind", "id", "quote"),
                            "additionalProperties", false)),
                    "caveats", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("answer", "evidence", "citations", "caveats"),
            "additionalProperties", false);

    private final ConversationRepository conversations;
    private final DocumentTextQuery documentTexts;
    private final ContractQueries contracts;
    private final TaskQueries tasks;
    private final ZoneId zone;
    private final Clock clock;

    public ChatHandler(ConversationRepository conversations, DocumentTextQuery documentTexts,
            ContractQueries contracts, TaskQueries tasks, ZoneId zone, Clock clock) {
        this.conversations = conversations;
        this.documentTexts = documentTexts;
        this.contracts = contracts;
        this.tasks = tasks;
        this.zone = zone;
        this.clock = clock;
    }

    @Override
    public Workload workload() {
        return Workload.CHAT;
    }

    @Override
    public Set<String> grantedTools() {
        return ChatTools.GRANTED;
    }

    @Override
    public PreparedRun prepare(RunContext run) {
        Conversation conversation = conversationOf(run);
        ChatTurn turn = turnOf(run, conversation);
        List<ModelMessage> messages = new ArrayList<>(history(conversation, turn));
        messages.add(ModelMessage.user(turn.question()));
        return new PreparedRun(system(turn.locale()), messages, OUTPUT_SCHEMA);
    }

    String system(ProductLocale locale) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return SYSTEM.formatted(today, today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH), zone,
                locale == ProductLocale.EN ? "English" : "German");
    }

    /**
     * Earlier answered questions, newest last, so follow-up questions can refer to them. Bounded by turns and size;
     * the oldest are left out first.
     */
    private static List<ModelMessage> history(Conversation conversation, ChatTurn current) {
        List<ModelMessage> exchange = new ArrayList<>();
        int chars = 0;
        int turns = 0;
        List<ChatTurn> earlier = conversation.turns().stream().filter(turn -> turn.seq() < current.seq())
                .filter(turn -> turn.answer().isPresent()).toList();
        for (int i = earlier.size() - 1; i >= 0 && turns < HISTORY_TURNS; i--) {
            ChatTurn turn = earlier.get(i);
            String answer = answerForHistory(turn.answer().orElseThrow());
            chars += turn.question().length() + answer.length();
            if (chars > HISTORY_CHARS) {
                break;
            }
            exchange.addFirst(new ModelMessage(ModelMessage.Role.ASSISTANT, List.of(new ContentPart.Text(answer)),
                    null));
            exchange.addFirst(ModelMessage.user(turn.question()));
            turns++;
        }
        return exchange;
    }

    private static String answerForHistory(ChatAnswer answer) {
        StringBuilder text = new StringBuilder(answer.text());
        if (!answer.citations().isEmpty()) {
            text.append("\n\nSources:");
            for (Citation citation : answer.citations()) {
                text.append("\n[").append(citation.number()).append("] ").append(citation.kind()).append(' ')
                        .append(citation.sourceId());
                if (citation.title() != null) {
                    text.append(" \"").append(citation.title()).append('"');
                }
            }
        }
        return text.toString();
    }

    @Override
    public ValidatedOutput validate(RunContext run, ModelTurn turn) {
        if (!(turn.structuredOutput() instanceof Map<?, ?> output)) {
            throw new InvalidModelOutputException("output.schemaMismatch", "the answer is not a JSON object");
        }
        String text = output.get("answer") instanceof String value ? value.strip() : "";
        if (text.isEmpty()) {
            throw new InvalidModelOutputException("output.schemaMismatch", "the answer text is missing");
        }
        if (text.length() > ChatAnswer.MAX_TEXT) {
            throw new InvalidModelOutputException("output.tooLarge",
                    "the answer exceeds " + ChatAnswer.MAX_TEXT + " characters");
        }
        Evidence evidence = enumValue(Evidence.class, output.get("evidence"), "evidence");
        List<Citation> citations = citations(run, output.get("citations"));
        List<String> caveats = caveats(output.get("caveats"));
        ChatAnswer answer = new ChatAnswer(text, evidence, citations, caveats);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("turn", run.parameters().get(TURN));
        summary.put("evidence", evidence.name());
        summary.put("citations", citations.size());
        summary.put("verifiedCitations", citations.stream().filter(c -> c.check() == Check.VERIFIED).count());
        return new ValidatedOutput(JsonText.write(summary), answer);
    }

    private List<Citation> citations(RunContext run, Object value) {
        if (!(value instanceof List<?> items)) {
            throw new InvalidModelOutputException("output.schemaMismatch", "citations must be a list");
        }
        if (items.size() > ChatAnswer.MAX_CITATIONS) {
            throw new InvalidModelOutputException("output.schemaMismatch",
                    "at most " + ChatAnswer.MAX_CITATIONS + " citations are allowed");
        }
        Map<UUID, Optional<DocumentText>> texts = new HashMap<>();
        List<Citation> citations = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new InvalidModelOutputException("output.schemaMismatch", "a citation must be an object");
            }
            SourceKind kind = enumValue(SourceKind.class, entry.get("kind"), "citation kind");
            UUID id = uuid(entry.get("id"));
            String quote = entry.get("quote") instanceof String raw ? bounded(raw.strip()) : "";
            int number = citations.size() + 1;
            citations.add(switch (kind) {
                case DOCUMENT -> documentCitation(run, number, id, quote, texts);
                case CONTRACT -> contractCitation(run, number, id, quote);
                case TASK -> taskCitation(run, number, id, quote);
            });
        }
        return citations;
    }

    private Citation documentCitation(RunContext run, int number, UUID id, String quote,
            Map<UUID, Optional<DocumentText>> texts) {
        Optional<DocumentText> text = texts.computeIfAbsent(id, key -> readable(() -> documentTexts.text(run.actor(),
                new DocumentId(key))));
        if (text.isEmpty()) {
            return new Citation(number, SourceKind.DOCUMENT, id, null, blankToNull(quote), null,
                    Check.SOURCE_UNAVAILABLE);
        }
        Optional<QuoteMatch.Found> found = quote.isEmpty() ? Optional.empty()
                : QuoteMatch.find(text.get().text(), quote);
        return new Citation(number, SourceKind.DOCUMENT, id, text.get().title(), blankToNull(quote),
                found.flatMap(QuoteMatch.Found::page).orElse(null),
                found.isPresent() ? Check.VERIFIED : Check.NOT_IN_SOURCE);
    }

    private Citation contractCitation(RunContext run, int number, UUID id, String quote) {
        Optional<ContractView> contract = readable(() -> contracts.get(run.actor(), new ContractId(id)))
                .filter(view -> view.trash() == null);
        return new Citation(number, SourceKind.CONTRACT, id, contract.map(ContractView::name).orElse(null),
                blankToNull(quote), null, contract.isPresent() ? Check.VERIFIED : Check.SOURCE_UNAVAILABLE);
    }

    private Citation taskCitation(RunContext run, int number, UUID id, String quote) {
        Optional<TaskSummary> task = readable(() -> tasks.get(run.actor(), new TaskId(id)))
                .filter(view -> view.trash() == null);
        return new Citation(number, SourceKind.TASK, id, task.map(TaskSummary::title).orElse(null),
                blankToNull(quote), null, task.isPresent() ? Check.VERIFIED : Check.SOURCE_UNAVAILABLE);
    }

    /** A source that is unknown, in the trash, not archived or not readable right now cannot support a statement. */
    private static <T> Optional<T> readable(Supplier<T> read) {
        try {
            return Optional.of(read.get());
        } catch (NotFoundException | InvalidInputException | ContentUnavailableException
                | NotAuthorizedException unavailable) {
            return Optional.empty();
        }
    }

    private static List<String> caveats(Object value) {
        if (!(value instanceof List<?> items)) {
            throw new InvalidModelOutputException("output.schemaMismatch", "caveats must be a list");
        }
        List<String> caveats = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String caveat && !caveat.isBlank() && caveats.size() < ChatAnswer.MAX_CAVEATS) {
                String stripped = caveat.strip();
                caveats.add(stripped.length() <= ChatAnswer.MAX_CAVEAT ? stripped
                        : stripped.substring(0, ChatAnswer.MAX_CAVEAT - 1) + "…");
            }
        }
        return caveats;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String field) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException invalid) {
            throw new InvalidModelOutputException("output.schemaMismatch", field + " has an unknown value");
        }
    }

    private static UUID uuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value).strip());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidModelOutputException("output.unknownSource", "a citation names no valid id");
        }
    }

    private static String bounded(String quote) {
        return quote.length() <= Citation.MAX_QUOTE ? quote : quote.substring(0, Citation.MAX_QUOTE - 1) + "…";
    }

    private static String blankToNull(String quote) {
        return quote.isEmpty() ? null : quote;
    }

    @Override
    public Optional<String> effectKey(RunContext run, ValidatedOutput output) {
        return Optional.of("chat:" + run.parameters().get(TURN));
    }

    /** Stores the answer in the conversation; a deleted conversation or a superseded run changes nothing. */
    @Override
    public void apply(RunContext run, ValidatedOutput output) {
        UUID conversationId = parameter(run, CONVERSATION);
        UUID turnId = parameter(run, TURN);
        conversations.findById(conversationId).ifPresent(conversation -> {
            if (conversation.answer(turnId, run.id(), (ChatAnswer) output.value(), clock.instant())) {
                conversations.save(conversation);
            }
        });
    }

    private Conversation conversationOf(RunContext run) {
        UUID id = parameter(run, CONVERSATION);
        Conversation conversation = conversations.findById(id)
                .orElseThrow(() -> new NotFoundException("conversation", id.toString()));
        if (!conversation.ownedBy(run.actor().memberId())) {
            throw new NotAuthorizedException("conversation " + id + " belongs to another member");
        }
        return conversation;
    }

    private static ChatTurn turnOf(RunContext run, Conversation conversation) {
        UUID turnId = parameter(run, TURN);
        return conversation.turn(turnId)
                .orElseThrow(() -> new NotFoundException("chatTurn", turnId.toString()));
    }

    private static UUID parameter(RunContext run, String name) {
        try {
            return UUID.fromString(run.parameters().getOrDefault(name, ""));
        } catch (IllegalArgumentException invalid) {
            throw new NotFoundException(name, run.parameters().getOrDefault(name, ""));
        }
    }
}
