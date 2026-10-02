package dev.aegis.assistant.adapters.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.adapters.anthropic.AnthropicModelGateway;
import dev.aegis.assistant.adapters.anthropic.AnthropicProperties;
import dev.aegis.assistant.adapters.claudecode.ClaudeCodeGateway;
import dev.aegis.assistant.adapters.claudecode.ClaudeCodeProperties;
import dev.aegis.assistant.adapters.tools.DocumentSearchTool;
import dev.aegis.assistant.adapters.tools.DocumentTextSearchTool;
import dev.aegis.assistant.adapters.tools.HouseholdCatalogTool;
import dev.aegis.assistant.adapters.tools.ListContractsTool;
import dev.aegis.assistant.adapters.tools.ListTasksTool;
import dev.aegis.assistant.adapters.tools.ReadContractTool;
import dev.aegis.assistant.adapters.tools.ReadDocumentTool;
import dev.aegis.assistant.application.AiSettings;
import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.ModelCatalog;
import dev.aegis.assistant.application.ModelPrice;
import dev.aegis.assistant.application.PriceBook;
import dev.aegis.assistant.application.RunExecutor;
import dev.aegis.assistant.application.chat.ChatHandler;
import dev.aegis.assistant.application.chat.ChatRetrieval;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.chat.ChatAnswer;
import dev.aegis.assistant.domain.chat.Conversation;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LIVE evaluation of the household assistant with the reference questions (issue #25). It is skipped unless
 * {@code AEGIS_CHAT_EVAL=true} and costs money or subscription usage: about 0.04 USD API-equivalent per question.
 *
 * <p>The real chat handler, tools and run executor answer every question of {@code fixtures/assistant-eval} in a
 * household that holds only the synthetic reference documents, once per retrieval ({@code LABELS_AND_TEXT},
 * {@code TEXT_ONLY}) and repetition. The model is the Claude API when {@code AEGIS_ANTHROPIC_API_KEY_FILE} names a
 * key file, otherwise the owner's subscription through the host runner at {@code AEGIS_CLAUDE_RUNNER_SOCKET} (only
 * with the owner's consent). Optional: {@code AEGIS_CHAT_EVAL_MODES}, {@code AEGIS_CHAT_EVAL_QUESTIONS} (ids),
 * {@code AEGIS_CHAT_EVAL_REPETITIONS} (default 1), {@code AEGIS_CHAT_EVAL_MODEL} (default the chat's Sonnet model)
 * and {@code AEGIS_CHAT_EVAL_OUTPUT}.
 *
 * <p>It writes the raw results of every run as JSON (default {@code target/assistant-eval/}); score and summarize
 * them with {@code ops/eval/chat_reference_check.py report}. The test itself only fails when a run ends without an
 * answer, because answer quality is the subject of the evaluation, not a pass condition.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "AEGIS_CHAT_EVAL", matches = "true")
class ChatReferenceLiveTest {
    static final ZoneId HOUSEHOLD_ZONE = ZoneId.of("Europe/Berlin");
    /** List prices of the chat's default model as configured in application.yml. */
    static final ModelPrice SONNET = new ModelPrice(Money.of("2.00", "USD"), Money.of("10.00", "USD"),
            Money.of("0.20", "USD"), Money.of("2.50", "USD"));

    private final MemberId member = MemberId.random();
    private final Actor actor = new Actor(member);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void answersTheReferenceQuestionsWithBothRetrievals() throws IOException {
        ReferenceSet set = ReferenceSet.load();
        Clock clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(ChronoUnit.DAYS.between(
                LocalDate.now(HOUSEHOLD_ZONE), set.referenceDate())));
        SyntheticHousehold household = new SyntheticHousehold(set, member, clock.instant().minus(Duration.ofDays(1)));
        Provider provider = provider();
        String model = env("AEGIS_CHAT_EVAL_MODEL", "claude-sonnet-5-5");
        List<ChatRetrieval> modes = Arrays.stream(env("AEGIS_CHAT_EVAL_MODES", "LABELS_AND_TEXT,TEXT_ONLY")
                .split(",")).map(String::strip).map(ChatRetrieval::valueOf).toList();
        Set<String> only = new LinkedHashSet<>(List.of(env("AEGIS_CHAT_EVAL_QUESTIONS", "").split(",")));
        only.remove("");
        int repetitions = Integer.parseInt(env("AEGIS_CHAT_EVAL_REPETITIONS", "1"));
        Path output = Path.of(env("AEGIS_CHAT_EVAL_OUTPUT", "target/assistant-eval/in-process-"
                + Instant.now().toString().replace(":", "") + ".json"));

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                for (ChatRetrieval mode : modes) {
                    for (ReferenceSet.Question question : set.questions()) {
                        if (!only.isEmpty() && !only.contains(question.id())) {
                            continue;
                        }
                        Map<String, Object> result = ask(question, mode, repetition, household, provider, model,
                                clock);
                        results.add(result);
                        System.out.printf("EVAL %s %-15s #%d %-34s %s cited=%s %.1fs calls=%s tools=%s%n",
                                provider.name(), mode, repetition, question.id(), result.get("state"),
                                result.get("citations"), (double) result.get("latencySeconds"),
                                result.get("modelCalls"), result.get("tools"));
                    }
                }
            }
        } finally {
            if (provider.gateway() instanceof AnthropicModelGateway api) {
                api.destroy();
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("harness", "in-process");
            report.put("provider", provider.name());
            report.put("model", model);
            report.put("referenceSetVersion", set.version());
            report.put("referenceDate", set.referenceDate().toString());
            report.put("runs", results);
            Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("EVAL results written to " + output.toAbsolutePath());
        }
        assertThat(results).allSatisfy(result -> assertThat(result.get("state")).as(result.get("question") + " "
                + result.get("mode")).isEqualTo(RunState.SUCCEEDED.name()));
    }

    private Map<String, Object> ask(ReferenceSet.Question question, ChatRetrieval mode, int repetition,
            SyntheticHousehold household, Provider provider, String model, Clock clock) {
        InMemoryConversations conversations = new InMemoryConversations();
        ChatHandler handler = new ChatHandler(conversations, household, household.contracts(), household.tasks(),
                HOUSEHOLD_ZONE, clock, mode);
        UUID turn = UUID.randomUUID();
        RunId runId = RunId.random();
        Conversation conversation = Conversation.start(UUID.randomUUID(), member, turn, question.question(),
                ProductLocale.valueOf(question.locale()), runId, clock.instant());
        conversations.save(conversation);
        AiRun run = AiRun.request(runId, Workload.CHAT, "chat:" + turn, null, member, clock.instant(),
                new ModelSelection(provider.gateway().provider(), model), provider.budget(), List.of(),
                Map.of(ChatHandler.CONVERSATION, conversation.id().toString(), ChatHandler.TURN, turn.toString()));
        EvaluationRunExecution execution = new EvaluationRunExecution(run, actor, handler, clock);
        MeasuredGateway gateway = new MeasuredGateway(provider.gateway());
        List<ToolUse> uses = new ArrayList<>();
        RunExecutor executor = new RunExecutor(execution, Map.of(Workload.CHAT, handler),
                Map.of(gateway.provider(), gateway), tools(household, uses), settings(model));

        long started = System.nanoTime();
        for (int executions = 0; executions < 5; executions++) {
            if (!(executor.execute(runId) instanceof RunExecutor.RunAgainAt again)) {
                break;
            }
            execution.skipTo(again.at());
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("harness", "in-process");
        result.put("mode", mode.name());
        result.put("repetition", repetition);
        result.put("question", question.id());
        result.put("state", run.state().name());
        result.put("failure", run.failure().map(failure -> failure.code()).orElse(null));
        Optional<ChatAnswer> answer = conversations.findById(conversation.id()).flatMap(stored -> stored.turn(turn))
                .flatMap(stored -> stored.answer());
        result.put("evidence", answer.map(a -> a.evidence().name()).orElse(null));
        result.put("answer", answer.map(ChatAnswer::text).orElse(null));
        result.put("caveats", answer.map(ChatAnswer::caveats).orElse(List.of()));
        result.put("citations", answer.map(a -> a.citations().stream().map(citation -> {
            Map<String, Object> cited = new LinkedHashMap<>();
            cited.put("source", citation.kind() == ChatAnswer.SourceKind.DOCUMENT
                    ? household.keyOf(citation.sourceId()).orElse("unknown-document")
                    : citation.kind().name().toLowerCase(Locale.ROOT));
            cited.put("check", citation.check().name());
            cited.put("page", citation.page());
            return cited;
        }).toList()).orElse(List.of()));
        result.put("latencySeconds", Math.round(seconds * 10) / 10.0);
        result.put("modelCalls", run.modelCalls());
        result.put("toolCalls", run.toolCalls());
        Map<String, Integer> byTool = new LinkedHashMap<>();
        uses.forEach(use -> byTool.merge(use.name(), 1, Integer::sum));
        result.put("tools", byTool);
        TokenUsage tokens = run.tokens();
        result.put("inputTokens", tokens.inputTokens());
        result.put("cacheReadTokens", tokens.cacheReadTokens());
        result.put("cacheWriteTokens", tokens.cacheWriteTokens());
        result.put("outputTokens", tokens.outputTokens());
        result.put("cost", run.cost().amount());
        result.put("inputCharacters", gateway.inputCharacters());
        result.put("largestCallCharacters", gateway.largestCall());
        result.put("searchedDocuments", keys(household, uses, true));
        result.put("readDocuments", keys(household, uses, false));
        return result;
    }

    /** Reference keys of the documents the searches returned or the model read, in order of first appearance. */
    private List<String> keys(SyntheticHousehold household, List<ToolUse> uses, boolean searched) {
        Set<String> keys = new LinkedHashSet<>();
        for (ToolUse use : uses) {
            if (searched && use.name().startsWith("search_document") && !use.result().error()) {
                for (JsonNode document : json.readTree(use.result().content()).path("documents")) {
                    household.keyOf(UUID.fromString(document.path("id").asString())).ifPresent(keys::add);
                }
            }
            if (!searched && use.name().equals("read_document") && use.input().get("id") instanceof String id) {
                try {
                    household.keyOf(UUID.fromString(id)).ifPresent(keys::add);
                } catch (IllegalArgumentException notAnId) {
                    keys.add("invalid-id");
                }
            }
        }
        return List.copyOf(keys);
    }

    private static Map<String, AiTool> tools(SyntheticHousehold household, List<ToolUse> uses) {
        MemberDirectory nobody = new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor actor) {
                return List.of();
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return Optional.empty();
            }
        };
        Map<String, AiTool> tools = new HashMap<>();
        for (AiTool tool : List.of(new HouseholdCatalogTool(household), new DocumentSearchTool(household),
                new DocumentTextSearchTool(household), new ReadDocumentTool(household, household,
                        household.contracts()), new ListContractsTool(household.contracts()),
                new ReadContractTool(household.contracts()), new ListTasksTool(household.tasks(), nobody))) {
            tools.put(tool.spec().name(), new RecordedTool(tool, uses));
        }
        return tools;
    }

    private static AiSettings settings(String model) {
        Money daily = Money.of("50.00", "USD");
        return new AiSettings(Map.of(), daily, 1, Duration.ofSeconds(120), Duration.ofSeconds(15), HOUSEHOLD_ZONE,
                new PriceBook(Map.of(model, SONNET)), Map.of(), new ModelCatalog(Map.of()));
    }

    private record Provider(String name, ModelGateway gateway, RunBudget budget) {
    }

    /** The chat profile of application.yml; the subscription has its own run budget like in provider-limits. */
    private Provider provider() {
        String keyFile = System.getenv("AEGIS_ANTHROPIC_API_KEY_FILE");
        if (keyFile != null && !keyFile.isBlank()) {
            return new Provider("anthropic", new AnthropicModelGateway(new AnthropicProperties(keyFile, null, null),
                    json), budget("0.10"));
        }
        String socket = env("AEGIS_CLAUDE_RUNNER_SOCKET", "/srv/aegis/claude-runner/runner.sock");
        return new Provider("claude-code", new ClaudeCodeGateway(new ClaudeCodeProperties(socket, List.of(), 3),
                null, json), budget("0.50"));
    }

    private static RunBudget budget(String maxCost) {
        return new RunBudget(Duration.ofMinutes(5), Money.of(maxCost, "USD"), 8, 20, 8000, 2);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private record ToolUse(String name, Map<String, Object> input, AiTool.Result result) {
    }

    private record RecordedTool(AiTool tool, List<ToolUse> uses) implements AiTool {
        @Override
        public ToolSpec spec() {
            return tool.spec();
        }

        @Override
        public Result invoke(Invocation invocation, Map<String, Object> input) {
            Result result = tool.invoke(invocation, input);
            uses.add(new ToolUse(tool.spec().name(), input, result));
            return result;
        }
    }

    /**
     * Passes every call on and measures what the model was sent, in characters: system prompt, messages, tool
     * definitions and output schema. The member check is the application's business, not the evaluation's.
     */
    private static final class MeasuredGateway implements ModelGateway {
        private final ModelGateway inner;
        private long characters;
        private long largest;

        MeasuredGateway(ModelGateway inner) {
            this.inner = inner;
        }

        @Override
        public String provider() {
            return inner.provider();
        }

        @Override
        public boolean configured() {
            return inner.configured();
        }

        @Override
        public ModelTurn complete(ModelRequest request) {
            long size = request.system().length() + JsonText.write(request.tools()).length()
                    + (request.outputSchema() == null ? 0 : JsonText.write(request.outputSchema()).length());
            for (ModelMessage message : request.messages()) {
                for (ContentPart part : message.parts()) {
                    size += switch (part) {
                        case ContentPart.Text text -> text.text().length();
                        case ContentPart.ToolCall call -> call.name().length() + JsonText.write(call.input()).length();
                        case ContentPart.ToolResult result -> result.content().length();
                    };
                }
            }
            characters += size;
            largest = Math.max(largest, size);
            return inner.complete(request);
        }

        long inputCharacters() {
            return characters;
        }

        long largestCall() {
            return largest;
        }
    }

    private static final class InMemoryConversations implements ConversationRepository {
        private final Map<UUID, Conversation> stored = new HashMap<>();

        @Override
        public Optional<Conversation> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public List<ConversationHeader> findByOwner(MemberId owner, int limit) {
            return List.of();
        }

        @Override
        public void save(Conversation conversation) {
            conversation.stored(conversation.version() + 1);
            stored.put(conversation.id(), conversation);
        }

        @Override
        public void delete(UUID id) {
            stored.remove(id);
        }
    }
}
