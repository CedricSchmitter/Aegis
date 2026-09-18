package dev.aegis.assistant.application;

import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Assembles the AI execution boundary with controlled adapters: an in-memory run store, a scripted model, a test
 * workload with one granted and one ungranted tool, a mutable clock and a switchable household membership.
 */
class AiTestKit {
    static final Instant T0 = Instant.parse("2026-09-18T08:00:00Z");
    static final MemberId LISA = MemberId.random();
    static final MemberId CEDRIC = MemberId.random();
    static final Actor AS_LISA = new Actor(LISA);
    static final Actor AS_CEDRIC = new Actor(CEDRIC);
    static final String MODEL = "scripted-model";

    final MutableClock clock = new MutableClock(T0);
    final InMemoryAiRunRepository runs = new InMemoryAiRunRepository();
    final ScriptedGateway gateway = new ScriptedGateway();
    final Set<MemberId> members = new HashSet<>(Set.of(LISA, CEDRIC));
    final HouseholdAccess access = actor -> {
        if (actor == null || !members.contains(actor.memberId())) {
            throw new NotAuthorizedException("not a member");
        }
    };
    final List<RunId> scheduled = new CopyOnWriteArrayList<>();
    final RunScheduler scheduler = (id, at) -> scheduled.add(id);
    final Set<String> ledger = new HashSet<>();
    final ClassifierWorkload workload = new ClassifierWorkload();
    final LookupTool lookup = new LookupTool();
    final ForbiddenTool forbidden = new ForbiddenTool();

    RunBudget budget = new RunBudget(Duration.ofMinutes(2), Money.of("0.05", "USD"), 4, 3, 1024, 3);
    Money dailyLimit = Money.of("2.00", "USD");
    int maxConcurrentRuns = 2;
    int workloadConcurrency = 2;
    Map<String, ModelPrice> prices = new LinkedHashMap<>(Map.of(MODEL,
            new ModelPrice(Money.of("1", "USD"), Money.of("5", "USD"), null, null)));

    private AiSettings settings;
    private RunExecutionService execution;
    private RunExecutor executor;
    private AiRunService service;

    AiSettings settings() {
        if (settings == null) {
            Map<Workload, WorkloadProfile> profiles = new EnumMap<>(Workload.class);
            ModelSelection model = new ModelSelection(ScriptedGateway.PROVIDER, MODEL);
            profiles.put(Workload.DOCUMENT_CLASSIFICATION,
                    new WorkloadProfile(Workload.DOCUMENT_CLASSIFICATION, model, budget, workloadConcurrency, null));
            profiles.put(Workload.DOCUMENT_REVIEW,
                    new WorkloadProfile(Workload.DOCUMENT_REVIEW, model, budget, 1, "high"));
            profiles.put(Workload.CONNECTION_CHECK, new WorkloadProfile(Workload.CONNECTION_CHECK, null,
                    new RunBudget(Duration.ofSeconds(90), Money.of("0.05", "USD"), 1, 0, 256, 2), 2, null));
            settings = new AiSettings(profiles, dailyLimit, maxConcurrentRuns, Duration.ofSeconds(60),
                    ZoneId.of("Europe/Berlin"), new PriceBook(prices));
        }
        return settings;
    }

    Map<Workload, WorkloadHandler> handlers() {
        Map<Workload, WorkloadHandler> handlers = new EnumMap<>(Workload.class);
        handlers.put(Workload.DOCUMENT_CLASSIFICATION, workload);
        handlers.put(Workload.CONNECTION_CHECK, new ConnectionCheckHandler());
        return handlers;
    }

    RunExecutionService execution() {
        if (execution == null) {
            execution = new RunExecutionService(runs, () -> {
            }, (key, run, at) -> ledger.add(key), handlers(), access, settings(), clock);
        }
        return execution;
    }

    RunExecutor executor() {
        if (executor == null) {
            executor = new RunExecutor(execution(), handlers(), Map.of(ScriptedGateway.PROVIDER, gateway),
                    Map.of(lookup.spec().name(), lookup, forbidden.spec().name(), forbidden), settings());
        }
        return executor;
    }

    AiRunService service() {
        if (service == null) {
            service = new AiRunService(runs, scheduler, () -> {
            }, handlers(), Map.of(ScriptedGateway.PROVIDER, gateway), settings(), access, clock);
        }
        return service;
    }

    /** Requests a classification of document {@code documentId} at {@code version}. */
    RunView classify(String documentId, long version, String content, boolean reprocess) {
        return service().request(AS_LISA, new RunRequest(Workload.DOCUMENT_CLASSIFICATION.name(),
                "classification:" + documentId + ":" + version,
                List.of(new SourceVersion("document", documentId, version)), Map.of("content", content), reprocess));
    }

    RunId classify(String content) {
        return new RunId(classify("doc-1", 3, content, false).id());
    }

    /** Test workload: classifies a document into one of two labels using the granted {@code lookup} tool. */
    static final class ClassifierWorkload implements WorkloadHandler {
        static final Set<String> LABELS = Set.of("invoice", "insurance");
        final List<String> applied = new CopyOnWriteArrayList<>();
        boolean sourcesCurrent = true;
        int prepared;

        @Override
        public Workload workload() {
            return Workload.DOCUMENT_CLASSIFICATION;
        }

        @Override
        public Set<String> grantedTools() {
            return Set.of("lookup");
        }

        @Override
        public PreparedRun prepare(RunContext run) {
            prepared++;
            String document = UntrustedContent.wrap("document", run.sources().getFirst().id(),
                    run.parameters().getOrDefault("content", ""), 4_000);
            return new PreparedRun("Classify the household document.",
                    List.of(ModelMessage.user("Choose one label for this document.\n" + document)),
                    Map.of("type", "object", "properties", Map.of("label", Map.of("type", "string")),
                            "required", List.of("label"), "additionalProperties", false));
        }

        @Override
        public ValidatedOutput validate(RunContext run, ModelTurn turn) {
            if (turn.structuredOutput() instanceof Map<?, ?> answer && answer.size() == 1
                    && answer.get("label") instanceof String label && LABELS.contains(label)) {
                return new ValidatedOutput(JsonText.write(answer), label);
            }
            throw new InvalidModelOutputException("output.schemaMismatch", "label outside the vocabulary");
        }

        @Override
        public boolean sourcesCurrent(RunContext run) {
            return sourcesCurrent;
        }

        @Override
        public Optional<String> effectKey(RunContext run, ValidatedOutput output) {
            var source = run.sources().getFirst();
            return Optional.of("classification:" + source.id() + ":" + source.version());
        }

        @Override
        public void apply(RunContext run, ValidatedOutput output) {
            applied.add(run.sources().getFirst().id() + "=" + output.value());
        }
    }

    /** Granted read-only tool; checks household membership like every application query does. */
    final class LookupTool implements AiTool {
        final List<Actor> callers = new ArrayList<>();

        @Override
        public ToolSpec spec() {
            return new ToolSpec("lookup", "Looks up known organizations.",
                    Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")),
                            "required", List.of("name"), "additionalProperties", false));
        }

        @Override
        public Result invoke(Invocation invocation, Map<String, Object> input) {
            access.requireMember(invocation.actor());
            callers.add(invocation.actor());
            return Result.ok(JsonText.write(Map.of("organization", String.valueOf(input.get("name")))));
        }
    }

    /** Registered but never granted to the test workload; must never run. */
    static final class ForbiddenTool implements AiTool {
        int invocations;

        @Override
        public ToolSpec spec() {
            return new ToolSpec("delete_everything", "Deletes all documents.", Map.of("type", "object"));
        }

        @Override
        public Result invoke(Invocation invocation, Map<String, Object> input) {
            invocations++;
            return Result.ok("deleted");
        }
    }

    /** Clock that tests move explicitly. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
