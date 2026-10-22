package dev.aegis.assistant.adapters.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.adapters.tools.FindInDocumentTool;
import dev.aegis.assistant.adapters.tools.ReadDocumentTool;
import dev.aegis.assistant.application.*;
import dev.aegis.assistant.application.chat.*;
import dev.aegis.assistant.application.ports.*;
import dev.aegis.assistant.domain.*;
import dev.aegis.assistant.domain.chat.*;
import dev.aegis.shared.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline comparison for issue #165 with the real tools, executor and citation check and a scripted model: the
 * cancellation clause on page 14 of a 15-page contract is found with {@code find_in_document} in one tool call, while
 * reading part by part needs one call per part up to the clause. Both answers cite the clause on its page.
 */
class LongDocumentRegressionTest {
    private static final String CLAUSE = "Der Vertrag kann von beiden Seiten mit einer Frist von drei Monaten zum Ende "
            + "der Mindestlaufzeit schriftlich gekündigt werden.";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void findingTheClauseNeedsFewerToolCallsAndLessInputThanReadingPartByPart() throws Exception {
        Measurement reading = run(LongDocumentRegressionTest::readPartByPart);
        Measurement finding = run(LongDocumentRegressionTest::findFirst);

        for (Measurement measurement : List.of(reading, finding)) {
            assertThat(measurement.answer().citations()).singleElement().satisfies(citation -> {
                assertThat(citation.check()).isEqualTo(ChatAnswer.Check.VERIFIED);
                assertThat(citation.page()).isEqualTo(14);
            });
        }
        assertThat(reading.toolCalls()).isGreaterThanOrEqualTo(4);
        assertThat(finding.toolCalls()).isEqualTo(1);
        assertThat(finding.inputCharacters()).isLessThan(reading.inputCharacters() / 4);
        System.out.printf("long document: reading part by part %d tool calls, %d model calls, %d input characters; "
                + "find_in_document %d tool calls, %d model calls, %d input characters%n", reading.toolCalls(),
                reading.modelCalls(), reading.inputCharacters(), finding.toolCalls(), finding.modelCalls(),
                finding.inputCharacters());
    }

    /** Reads from the start and follows nextOffset until a part holds the clause. */
    private static Map<String, Object> readPartByPart(Step step) {
        if (step.lastResult() != null && step.lastResult().path("text").asString().contains(CLAUSE)) {
            return null;
        }
        int offset = step.lastResult() == null ? 0 : step.lastResult().path("nextOffset").asInt();
        return Map.of("tool", ChatTools.READ_DOCUMENT, "id", step.document(), "offset", offset);
    }

    /** Searches once for words of the clause; the best passage holds it. */
    private static Map<String, Object> findFirst(Step step) {
        if (step.lastResult() != null) {
            JsonNode best = step.lastResult().path("passages").path(0);
            assertThat(best.path("page").asInt()).isEqualTo(14);
            assertThat(best.path("text").asString()).contains(CLAUSE);
            return null;
        }
        return Map.of("tool", ChatTools.FIND_IN_DOCUMENT, "id", step.document(),
                "query", "Kündigung Frist Monate gekündigt");
    }

    private record Step(String document, JsonNode lastResult) {
    }

    private record Measurement(ChatAnswer answer, int toolCalls, int modelCalls, long inputCharacters) {
    }

    private Measurement run(Function<Step, Map<String, Object>> script) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC);
        MemberId member = MemberId.random();
        Actor actor = new Actor(member);
        ReferenceSet set = ReferenceSet.load();
        List<ReferenceSet.Document> documents = new ArrayList<>(set.documents());
        documents.add(contract(set.documents().getFirst().folder()));
        SyntheticHousehold household = new SyntheticHousehold(new ReferenceSet(set.description(), set.version(),
                set.referenceDate(), set.folderRoot(), documents, set.questions()), member, clock.instant());
        String document = UUID.nameUUIDFromBytes("assistant-eval:long-contract".getBytes(StandardCharsets.UTF_8))
                .toString();
        Conversations conversations = new Conversations();
        ChatHandler handler = new ChatHandler(conversations, household, household.contracts(), household.tasks(),
                ZoneId.of("Europe/Berlin"), clock, ChatRetrieval.LABELS_AND_TEXT);
        RunId id = RunId.random();
        UUID turn = UUID.randomUUID();
        Conversation conversation = Conversation.start(UUID.randomUUID(), member, turn,
                "Wie kann ich den Rahmenvertrag kündigen?", ProductLocale.DE, id, clock.instant());
        conversations.save(conversation);
        long[] input = new long[1];
        ModelGateway model = new ModelGateway() {
            int calls;

            public String provider() {
                return "controlled";
            }

            public boolean configured() {
                return true;
            }

            public ModelTurn complete(ModelRequest request) {
                input[0] += size(request);
                JsonNode last = null;
                if (calls > 0) {
                    last = JSON.readTree(((ContentPart.ToolResult) request.messages().getLast().parts().getFirst())
                            .content());
                }
                Map<String, Object> next = script.apply(new Step(document, last));
                if (next != null) {
                    Map<String, Object> arguments = new HashMap<>(next);
                    String tool = (String) arguments.remove("tool");
                    return new ModelTurn(List.of(new ContentPart.ToolCall("c" + calls++, tool, arguments)),
                            ModelTurn.Stop.TOOL_USE, TokenUsage.NONE, "synthetic", "", null, null);
                }
                return new ModelTurn(List.of(), ModelTurn.Stop.END, TokenUsage.NONE, "synthetic", "",
                        Map.of("answer", "Mit einer Frist von drei Monaten zum Ende der Mindestlaufzeit, schriftlich "
                                + "[1].", "evidence", "COMPLETE", "citations", List.of(Map.of("kind", "DOCUMENT",
                                "id", document, "quote", CLAUSE)), "caveats", List.of()), null);
            }
        };
        Map<String, AiTool> tools = Map.of(
                ChatTools.READ_DOCUMENT, new ReadDocumentTool(household, household, household.contracts()),
                ChatTools.FIND_IN_DOCUMENT, new FindInDocumentTool(household));
        RunBudget budget = new RunBudget(Duration.ofSeconds(30), Money.of("0.05", "USD"), 8, 8, 1024, 1);
        AiRun run = AiRun.request(id, Workload.CHAT, "chat:" + turn, null, member, clock.instant(),
                new ModelSelection(model.provider(), "synthetic"), budget, List.of(),
                Map.of(ChatHandler.CONVERSATION, conversation.id().toString(), ChatHandler.TURN, turn.toString()));
        var execution = new EvaluationRunExecution(run, actor, handler, clock);
        var settings = new AiSettings(Map.of(), Money.of("1", "USD"), 1, Duration.ofSeconds(10),
                Duration.ofSeconds(1), ZoneId.of("Europe/Berlin"), new PriceBook(Map.of("synthetic",
                new ModelPrice(Money.of("1", "USD"), Money.of("5", "USD"), null, null))), Map.of(),
                new ModelCatalog(Map.of()));
        new RunExecutor(execution, Map.of(Workload.CHAT, handler), Map.of(model.provider(), model), tools, settings)
                .execute(id);
        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        return new Measurement(conversation.latest().answer().orElseThrow(), run.toolCalls(), run.modelCalls(),
                input[0]);
    }

    /** Characters the model receives in one call: instructions, offered tools and the whole conversation so far. */
    private static long size(ModelRequest request) {
        long size = request.system().length() + JsonText.write(request.tools()).length();
        for (ModelMessage message : request.messages()) {
            for (ContentPart part : message.parts()) {
                size += switch (part) {
                    case ContentPart.Text text -> text.text().length();
                    case ContentPart.ToolCall call -> JsonText.write(call.input()).length();
                    case ContentPart.ToolResult result -> result.content().length();
                };
            }
        }
        return size;
    }

    /**
     * A 15-page framework contract of about 3,000 characters per page whose table of contents names the cancellation
     * section; the clause itself stands on page 14.
     */
    private static ReferenceSet.Document contract(List<String> folder) {
        List<List<String>> pages = new ArrayList<>();
        for (int page = 1; page <= 15; page++) {
            List<String> lines = new ArrayList<>();
            lines.add("Rahmenvertrag Haustechnik RV-2026/015 – Seite " + page + " von 15");
            if (page == 1) {
                lines.add("Inhalt: § 1 Gegenstand, § 5 Leistungen, § 12 Vergütung, § 23 Kündigung (Seite 14)");
            }
            for (int section = 1; section <= 12; section++) {
                lines.add("§ " + page + "." + section + " Der Auftragnehmer erbringt die vereinbarten Wartungs- und "
                        + "Prüfarbeiten an Heizung, Lüftung und Sanitär im Objekt Musterweg " + section + " nach dem "
                        + "jeweils gültigen Leistungsverzeichnis, Anlage " + page + ".");
                lines.add("Abrechnung und Nachweise erfolgen quartalsweise gegen Rechnung; Abweichungen werden im "
                        + "Prüfprotokoll vermerkt und gemeinsam besprochen.");
            }
            if (page == 14) {
                lines.add("§ 23 Kündigung");
                lines.add(CLAUSE);
            }
            pages.add(lines);
        }
        return new ReferenceSet.Document("long-contract", "Rahmenvertrag Haustechnik", LocalDate.of(2026, 1, 15),
                folder, List.of(), null, pages);
    }

    private static class Conversations implements ConversationRepository {
        private final Map<UUID, Conversation> stored = new HashMap<>();

        public Optional<Conversation> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        public List<ConversationHeader> findByOwner(MemberId owner, int limit) {
            return List.of();
        }

        public List<ConversationHeader> searchByOwner(MemberId owner, List<String> words, int limit) {
            return List.of();
        }

        public void save(Conversation conversation) {
            conversation.stored(conversation.version() + 1);
            stored.put(conversation.id(), conversation);
        }

        public void delete(UUID id) {
            stored.remove(id);
        }
    }
}
