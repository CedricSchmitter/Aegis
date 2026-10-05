package dev.aegis.assistant.adapters.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import dev.aegis.assistant.adapters.tools.DocumentSearchTool;
import dev.aegis.assistant.adapters.tools.DocumentTextSearchTool;
import dev.aegis.assistant.application.*;
import dev.aegis.assistant.application.chat.*;
import dev.aegis.assistant.application.ports.*;
import dev.aegis.assistant.domain.*;
import dev.aegis.assistant.domain.chat.*;
import dev.aegis.documents.application.api.*;
import dev.aegis.shared.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Offline extension of the reference harness: real tools, executor and guard, scripted model; no live credentials. */
class ChatCoverageRegressionTest {
    private static final Map<String, Object> VALID_SEARCH = Map.of("query", "policy");

    @Test
    void incompleteSearchCannotBeStoredAsCompleteOrAsProofOfAbsence() throws Exception {
        for (ChatRetrieval mode : ChatRetrieval.values()) {
            for (ContentSearchState state : List.of(ContentSearchState.LIMITED, ContentSearchState.UNAVAILABLE)) {
                for (String proposed : List.of("COMPLETE", "NOT_FOUND")) {
                    for (ProductLocale locale : ProductLocale.values()) {
                        var result = new DocumentSearchResult(List.of(), state, 1000, 0, 0, 10, FacetCounts.EMPTY, 0,
                                0, SearchLimitation.ARCHIVE_FAILURE, 700L, CountAccuracy.LOWER_BOUND);
                        Outcome outcome = run(mode, locale, result, List.of(VALID_SEARCH), proposed);
                        var json = JsonMapper.builder().build().readTree(outcome.lastToolResult());
                        assertThat(outcome.lastToolResult().length()).isLessThan(20_000);
                        assertThat(json.path("contentSearch").asString()).isEqualTo(state.name());
                        assertThat(json.path("countAccuracy").asString()).isEqualTo("LOWER_BOUND");
                        assertThat(json.path("archiveTotal").asLong()).isEqualTo(700);
                        assertThat(outcome.answer().evidence()).isEqualTo(ChatAnswer.Evidence.PARTIAL);
                        assertThat(outcome.answer().caveats()).singleElement().asString().contains(
                                locale == ProductLocale.EN ? "incomplete" : "unvollständig");
                        assertThat(outcome.run().modelCalls()).isEqualTo(2);
                        assertThat(outcome.run().toolCalls()).isEqualTo(1);
                        assertThat(outcome.run().output().orElseThrow()).contains("PARTIAL")
                                .doesNotContain("policies", "archiveTotal");
                    }
                }
            }
        }
    }

    @Test
    void rejectedSearchInputAndFailedArchivalAloneLeaveACompleteAnswerComplete() throws Exception {
        Map<String, Object> rejected = Map.of("query", "x".repeat(1_000));
        for (ChatRetrieval mode : ChatRetrieval.values()) {
            var result = new DocumentSearchResult(List.of(), ContentSearchState.READY, 30, 0, 0, 10,
                    FacetCounts.EMPTY, 0, 1, null, 0L, CountAccuracy.EXACT);
            Outcome outcome = run(mode, ProductLocale.DE, result, List.of(rejected, VALID_SEARCH), "COMPLETE");
            var json = JsonMapper.builder().build().readTree(outcome.lastToolResult());
            assertThat(json.path("archivalFailed").asLong()).isEqualTo(1);
            assertThat(outcome.answer().evidence()).isEqualTo(ChatAnswer.Evidence.COMPLETE);
            assertThat(outcome.answer().caveats()).isEmpty();
            assertThat(outcome.run().toolCalls()).isEqualTo(2);
        }
    }

    private record Outcome(AiRun run, ChatAnswer answer, String lastToolResult) {
    }

    private Outcome run(ChatRetrieval mode, ProductLocale locale, DocumentSearchResult searchResult,
            List<Map<String, Object>> searches, String proposed) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
        MemberId member = MemberId.random(); Actor actor = new Actor(member);
        SyntheticHousehold household = new SyntheticHousehold(ReferenceSet.load(), member, clock.instant());
        Conversations conversations = new Conversations();
        ChatHandler handler = new ChatHandler(conversations, household, household.contracts(), household.tasks(),
                ZoneId.of("Europe/Berlin"), clock, mode);
        RunId id = RunId.random(); UUID turn = UUID.randomUUID();
        Conversation conversation = Conversation.start(UUID.randomUUID(), member, turn, "All policies?", locale,
                id, clock.instant()); conversations.save(conversation);
        String name = mode == ChatRetrieval.TEXT_ONLY ? ChatTools.SEARCH_DOCUMENT_TEXT : ChatTools.SEARCH_DOCUMENTS;
        DocumentQueries queries = (DocumentQueries) java.lang.reflect.Proxy.newProxyInstance(
                DocumentQueries.class.getClassLoader(), new Class<?>[]{DocumentQueries.class}, (proxy, method, args) -> {
                    if (method.getName().equals("search")) {
                        assertThat(args[0]).isEqualTo(actor);
                        return searchResult;
                    }
                    return method.invoke(household, args);
                });
        AiTool tool = mode == ChatRetrieval.TEXT_ONLY ? new DocumentTextSearchTool(queries) : new DocumentSearchTool(queries);
        String[] lastToolResult = new String[1];
        ModelGateway model = new ModelGateway() {
            int calls;
            public String provider() { return "controlled"; }
            public boolean configured() { return true; }
            public ModelTurn complete(ModelRequest request) {
                if (calls > 0) {
                    lastToolResult[0] = ((ContentPart.ToolResult) request.messages().getLast().parts().getFirst())
                            .content();
                }
                if (calls < searches.size()) {
                    var call = new ContentPart.ToolCall("s" + calls, name, searches.get(calls++));
                    return new ModelTurn(List.of(call), ModelTurn.Stop.TOOL_USE, TokenUsage.NONE, "synthetic", "",
                            null, null);
                }
                return new ModelTurn(List.of(), ModelTurn.Stop.END, TokenUsage.NONE, "synthetic", "",
                        Map.of("answer", "There are no policies.", "evidence", proposed, "citations", List.of(),
                                "caveats", List.of()), null);
            }
        };
        RunBudget budget = new RunBudget(Duration.ofSeconds(30), Money.of("0.05", "USD"), searches.size() + 1,
                searches.size(), 1024, 1);
        AiRun run = AiRun.request(id, Workload.CHAT, "chat:" + turn, null, member, clock.instant(),
                new ModelSelection(model.provider(), "synthetic"), budget, List.of(),
                Map.of(ChatHandler.CONVERSATION, conversation.id().toString(), ChatHandler.TURN, turn.toString()));
        var execution = new EvaluationRunExecution(run, actor, handler, clock);
        var settings = new AiSettings(Map.of(), Money.of("1", "USD"), 1, Duration.ofSeconds(10),
                Duration.ofSeconds(1), ZoneId.of("Europe/Berlin"), new PriceBook(Map.of("synthetic",
                new ModelPrice(Money.of("1", "USD"), Money.of("5", "USD"), null, null))), Map.of(),
                new ModelCatalog(Map.of()));
        new RunExecutor(execution, Map.of(Workload.CHAT, handler), Map.of(model.provider(), model), Map.of(name, tool),
                settings).execute(id);
        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        return new Outcome(run, conversation.latest().answer().orElseThrow(), lastToolResult[0]);
    }

    private static class Conversations implements ConversationRepository {
        private final Map<UUID, Conversation> stored = new HashMap<>();
        public Optional<Conversation> findById(UUID id) { return Optional.ofNullable(stored.get(id)); }
        public List<ConversationHeader> findByOwner(MemberId owner, int limit) { return List.of(); }
        public List<ConversationHeader> searchByOwner(MemberId owner, List<String> words, int limit) { return List.of(); }
        public void save(Conversation conversation) {
            conversation.stored(conversation.version() + 1); stored.put(conversation.id(), conversation);
        }
        public void delete(UUID id) { stored.remove(id); }
    }
}
