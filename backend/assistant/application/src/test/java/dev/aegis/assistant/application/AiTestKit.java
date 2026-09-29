package dev.aegis.assistant.application;

import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadModelChoice;
import dev.aegis.assistant.domain.WorkloadModelChoiceRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
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
    /** Catalog model of the Sonnet family, the default of every profile. */
    static final String MODEL = "claude-sonnet-test";
    static final String OPUS = "claude-opus-test";
    /** Catalog model without effort levels, like Haiku 4.5. */
    static final String HAIKU = "claude-haiku-test";
    static final String SUBSCRIPTION = "subscription";

    final MutableClock clock = new MutableClock(T0);
    final InMemoryAiRunRepository runs = new InMemoryAiRunRepository();
    final InMemoryModelChoices choices = new InMemoryModelChoices();
    final ScriptedGateway gateway = new ScriptedGateway();
    /** Second choice of every profile, like the owner's subscription: not configured and limited to Cedric. */
    final ScriptedGateway subscription = new ScriptedGateway(SUBSCRIPTION);

    {
        subscription.configured = false;
        subscription.allowed = Set.of(CEDRIC);
    }
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
    /** The subscription has its own limits, like the owner's Claude subscription in production. */
    Map<String, ProviderLimits> providerLimits = new LinkedHashMap<>(Map.of(SUBSCRIPTION,
            new ProviderLimits(Money.of("50.00", "USD"), Money.of("0.50", "USD"))));
    int maxConcurrentRuns = 2;
    int workloadConcurrency = 2;
    String classificationEffort;
    Map<String, ModelPrice> prices = new LinkedHashMap<>(Map.of(
            MODEL, new ModelPrice(Money.of("1", "USD"), Money.of("5", "USD"), null, null),
            OPUS, new ModelPrice(Money.of("4", "USD"), Money.of("20", "USD"), null, null),
            HAIKU, new ModelPrice(Money.of("1", "USD"), Money.of("5", "USD"), null, null)));
    Map<ModelFamily, ModelCatalog.Entry> catalog = new LinkedHashMap<>(Map.of(
            ModelFamily.SONNET, new ModelCatalog.Entry(ModelFamily.SONNET, MODEL, true),
            ModelFamily.OPUS, new ModelCatalog.Entry(ModelFamily.OPUS, OPUS, true),
            ModelFamily.HAIKU, new ModelCatalog.Entry(ModelFamily.HAIKU, HAIKU, false)));

    private AiSettings settings;
    private RunExecutionService execution;
    private RunExecutor executor;
    private AiRunService service;

    AiSettings settings() {
        if (settings == null) {
            Map<Workload, WorkloadProfile> profiles = new EnumMap<>(Workload.class);
            List<String> providers = List.of(ScriptedGateway.PROVIDER, SUBSCRIPTION);
            profiles.put(Workload.DOCUMENT_CLASSIFICATION, new WorkloadProfile(Workload.DOCUMENT_CLASSIFICATION,
                    providers, ModelFamily.SONNET, budget, workloadConcurrency, classificationEffort));
            profiles.put(Workload.DOCUMENT_REVIEW,
                    new WorkloadProfile(Workload.DOCUMENT_REVIEW, providers, ModelFamily.SONNET, budget, 1, "high"));
            profiles.put(Workload.CONNECTION_CHECK, new WorkloadProfile(Workload.CONNECTION_CHECK, List.of(), null,
                    new RunBudget(Duration.ofSeconds(90), Money.of("0.05", "USD"), 1, 0, 256, 2), 2, null));
            settings = new AiSettings(profiles, dailyLimit, maxConcurrentRuns, Duration.ofSeconds(60),
                    ZoneId.of("Europe/Berlin"), new PriceBook(prices), providerLimits, new ModelCatalog(catalog));
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
            executor = new RunExecutor(execution(), handlers(), gateways(),
                    Map.of(lookup.spec().name(), lookup, forbidden.spec().name(), forbidden), settings());
        }
        return executor;
    }

    Map<String, ModelGateway> gateways() {
        return Map.of(ScriptedGateway.PROVIDER, gateway, SUBSCRIPTION, subscription);
    }

    AiRunService service() {
        if (service == null) {
            service = new AiRunService(runs, choices, scheduler, () -> {
            }, handlers(), gateways(), settings(), access, clock);
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

    /** Model choices in memory with the same version check as the database. */
    static final class InMemoryModelChoices implements WorkloadModelChoiceRepository {
        private final Map<Workload, WorkloadModelChoice> stored = new EnumMap<>(Workload.class);

        @Override
        public synchronized Optional<WorkloadModelChoice> find(Workload workload) {
            WorkloadModelChoice choice = stored.get(workload);
            return Optional.ofNullable(choice).map(found -> WorkloadModelChoice.restore(found.workload(),
                    found.family(), found.version(), found.changedBy().orElse(null), found.changedAt().orElse(null)));
        }

        @Override
        public synchronized Map<Workload, WorkloadModelChoice> findAll() {
            Map<Workload, WorkloadModelChoice> all = new EnumMap<>(Workload.class);
            stored.keySet().forEach(workload -> all.put(workload, find(workload).orElseThrow()));
            return all;
        }

        @Override
        public synchronized void save(WorkloadModelChoice choice) {
            long current = stored.containsKey(choice.workload()) ? stored.get(choice.workload()).version() : 0;
            if (current != choice.version() - 1) {
                throw new StaleVersionException("aiModelChoice", choice.workload().name(), choice.version() - 1,
                        current);
            }
            stored.put(choice.workload(), choice);
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
