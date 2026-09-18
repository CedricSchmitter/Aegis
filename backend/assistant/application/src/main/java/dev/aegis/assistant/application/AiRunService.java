package dev.aegis.assistant.application;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.assistant.application.api.AiRunStatusQuery;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RequestOutcome;
import dev.aegis.shared.NotAuthorizedException;
import java.util.Collection;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.WorkloadView;
import dev.aegis.assistant.application.ports.AiLock;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Member-facing AI use cases: requesting, inspecting, cancelling and retrying runs, and the connection check. Every
 * entry point checks household membership. Requests that cannot run (no credentials, no handler, unknown price) are
 * refused instead of queued. Wrapped in a transaction by the composition root; no call waits on a model.
 */
public class AiRunService implements RequestAiRun, AiRunQueries, AiRunCommands, AiHealthQuery, AiRunStatusQuery {
    public static final int MAX_LIST = 100;
    /** Codes of requests that cannot run with the current configuration or membership. */
    static final Set<String> UNAVAILABLE_CODES = Set.of("ai.notConfigured", "ai.workload.unavailable",
            "ai.price.unknown");

    private final AiRunRepository runs;
    private final RunScheduler scheduler;
    private final AiLock lock;
    private final Map<Workload, WorkloadHandler> handlers;
    private final Map<String, ModelGateway> gateways;
    private final AiSettings settings;
    private final HouseholdAccess access;
    private final Clock clock;

    public AiRunService(AiRunRepository runs, RunScheduler scheduler, AiLock lock,
            Map<Workload, WorkloadHandler> handlers, Map<String, ModelGateway> gateways, AiSettings settings,
            HouseholdAccess access, Clock clock) {
        this.runs = runs;
        this.scheduler = scheduler;
        this.lock = lock;
        this.handlers = Map.copyOf(handlers);
        this.gateways = Map.copyOf(gateways);
        this.settings = settings;
        this.access = access;
        this.clock = clock;
    }

    // Requests ------------------------------------------------------------------------------------------------------

    @Override
    public RunView request(Actor actor, RunRequest request) {
        access.requireMember(actor);
        Workload workload = workload(request.workload());
        if (workload == Workload.CONNECTION_CHECK) {
            throw new InvalidInputException("ai.workload.unavailable",
                    "Connection checks are started through the connection check command");
        }
        WorkloadProfile profile = settings.profile(workload).orElseThrow(() -> new InvalidInputException(
                "ai.workload.unavailable", "Workload " + workload + " has no configured profile"));
        ModelSelection model = profile.model();
        requireRunnable(actor, workload, model);
        List<SourceRef> sources = new ArrayList<>();
        for (SourceVersion source : request.sources()) {
            try {
                sources.add(new SourceRef(source.kind(), source.id(), source.version()));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new InvalidInputException("validation.failed", "Invalid run source");
            }
        }
        return view(create(actor, workload, request.idempotencyKey(), null, model, profile.budget(), sources,
                request.parameters(), request.reprocess()));
    }

    @Override
    public RequestOutcome tryRequest(Actor actor, RunRequest request) {
        try {
            access.requireMember(actor);
        } catch (NotAuthorizedException notMember) {
            return new RequestOutcome.Refused("auth.forbidden");
        }
        try {
            return new RequestOutcome.Requested(request(actor, request));
        } catch (InvalidInputException refused) {
            if (UNAVAILABLE_CODES.contains(refused.code())) {
                return new RequestOutcome.Refused(refused.code());
            }
            throw refused;
        }
    }

    @Override
    public List<RunView> checkConnection(Actor actor) {
        access.requireMember(actor);
        WorkloadProfile check = settings.profile(Workload.CONNECTION_CHECK).orElseThrow(() ->
                new InvalidInputException("ai.notConfigured", "No connection check profile is configured"));
        Map<ModelSelection, Set<String>> targets = new LinkedHashMap<>();
        for (Workload workload : Workload.values()) {
            settings.profile(workload).flatMap(WorkloadProfile::modelSelection)
                    .filter(model -> providerConfigured(model) && settings.prices().find(model.model()).isPresent())
                    .ifPresent(model -> targets.computeIfAbsent(model, key -> new LinkedHashSet<>())
                            .add(workload.name()));
        }
        if (targets.isEmpty() || !handlers.containsKey(Workload.CONNECTION_CHECK)) {
            throw new InvalidInputException("ai.notConfigured",
                    "No AI provider with credentials and a priced model is configured");
        }
        List<RunView> started = new ArrayList<>();
        targets.forEach((model, workloads) -> started.add(view(create(actor, Workload.CONNECTION_CHECK,
                "connection-check:" + model.provider() + ":" + model.model() + ":" + String.join(",", workloads), null,
                model, check.budget(), List.of(),
                Map.of("workloads", String.join(",", workloads)), true))));
        return started;
    }

    @Override
    public RunView retry(Actor actor, UUID runId) {
        access.requireMember(actor);
        AiRun previous = load(runId);
        if (!previous.retryable()) {
            throw new InvalidInputException("ai.run.notRetryable", "Only failed or cancelled runs can be retried");
        }
        requireRunnable(actor, previous.workload(), previous.model());
        return view(create(actor, previous.workload(), previous.idempotencyKey(), previous.id(), previous.model(),
                previous.budget(), previous.sources(), previous.parameters(), true));
    }

    @Override
    public RunView cancel(Actor actor, UUID runId) {
        access.requireMember(actor);
        AiRun run = load(runId, true);
        if (run.requestCancel(actor.memberId(), clock.instant())) {
            runs.save(run);
        }
        return view(run);
    }

    private AiRun create(Actor actor, Workload workload, String key, RunId retryOf, ModelSelection model,
            RunBudget budget, List<SourceRef> sources, Map<String, String> parameters, boolean reprocess) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new InvalidInputException("validation.failed", "The idempotency key needs 1 to 200 characters");
        }
        lock.exclusive();
        Optional<AiRun> active = runs.findActiveByKey(key);
        Optional<AiRun> latest = runs.findLatestByKey(key);
        for (AiRun existing : List.of(active, latest).stream().flatMap(Optional::stream).toList()) {
            if (!existing.sameWork(workload, sources, parameters)) {
                throw new InvalidInputException("ai.run.keyConflict",
                        "The idempotency key already identifies different AI work", Map.of("key", key));
            }
        }
        if (active.isPresent()) {
            return active.get();
        }
        if (!reprocess) {
            Optional<AiRun> succeeded = latest.filter(run -> run.state() == RunState.SUCCEEDED);
            if (succeeded.isPresent()) {
                return succeeded.get();
            }
        }
        Instant now = clock.instant();
        AiRun run = AiRun.request(RunId.random(), workload, key, retryOf, actor.memberId(), now, model, budget,
                sources, parameters);
        runs.save(run);
        scheduler.schedule(run.id(), now);
        return run;
    }

    private void requireRunnable(Actor actor, Workload workload, ModelSelection model) {
        if (!handlers.containsKey(workload)) {
            throw new InvalidInputException("ai.workload.unavailable",
                    "Workload " + workload + " has no implemented workflow yet");
        }
        if (!providerConfigured(model)) {
            throw new InvalidInputException("ai.notConfigured",
                    "Provider " + model.provider() + " has no server-side credentials");
        }
        if (settings.prices().find(model.model()).isEmpty()) {
            throw new InvalidInputException("ai.price.unknown", "No price is configured for " + model.model());
        }
    }

    private boolean providerConfigured(ModelSelection model) {
        ModelGateway gateway = gateways.get(model.provider());
        return gateway != null && gateway.configured();
    }

    // Queries -------------------------------------------------------------------------------------------------------

    @Override
    public AiOverview overview(Actor actor) {
        access.requireMember(actor);
        Instant now = clock.instant();
        boolean configured = anyProviderConfigured();
        List<WorkloadView> workloads = new ArrayList<>();
        for (Workload workload : Workload.values()) {
            Optional<WorkloadProfile> profile = settings.profile(workload);
            if (profile.isPresent()) {
                workloads.add(workloadView(profile.get()));
            }
        }
        return new AiOverview(configured, configured, settings.dailyCostLimit().amount(),
                runs.costSince(settings.startOfDay(now), settings.currency()).amount(),
                settings.currency().getCurrencyCode(), settings.maxConcurrentRuns(), workloads);
    }

    private WorkloadView workloadView(WorkloadProfile profile) {
        ModelSelection model = profile.model();
        boolean handler = handlers.containsKey(profile.workload());
        boolean provider = model == null ? anyProviderConfigured() : providerConfigured(model);
        boolean priced = model == null || settings.prices().find(model.model()).isPresent();
        RunBudget budget = profile.budget();
        return new WorkloadView(profile.workload().name(), model == null ? null : model.provider(),
                model == null ? null : model.model(), provider, handler, handler && provider && priced,
                budget.maxCost().amount(), budget.maxDuration().toSeconds(), budget.maxModelCalls(),
                budget.maxToolCalls(), budget.maxOutputTokens(), budget.maxAttempts(), profile.maxConcurrent());
    }

    @Override
    public List<RunView> recent(Actor actor, int limit) {
        access.requireMember(actor);
        int bounded = Math.max(1, Math.min(limit, MAX_LIST));
        return runs.findRecent(bounded).stream().map(run -> RunViews.view(run, List.of())).toList();
    }

    @Override
    public Map<String, RunView> latestByKeys(Collection<String> idempotencyKeys) {
        Map<String, RunView> views = new LinkedHashMap<>();
        if (idempotencyKeys.isEmpty()) {
            return views;
        }
        runs.findLatestByKeys(idempotencyKeys).forEach((key, run) -> views.put(key, RunViews.view(run, List.of())));
        return views;
    }

    @Override
    public RunView get(Actor actor, UUID runId) {
        access.requireMember(actor);
        return view(load(runId));
    }

    /**
     * Health per currently configured model: its latest succeeded or failed run of any workload. Models that are no
     * longer configured do not affect the status.
     */
    @Override
    public AiHealth health(Actor actor) {
        access.requireMember(actor);
        Map<ModelSelection, AiRun> latest = new LinkedHashMap<>();
        for (AiRun run : runs.findLatestOutcomePerModel()) {
            latest.put(run.model(), run);
        }
        Set<ModelSelection> configured = new LinkedHashSet<>();
        for (Workload workload : Workload.values()) {
            settings.profile(workload).flatMap(WorkloadProfile::modelSelection).ifPresent(configured::add);
        }
        List<AiHealth.ModelHealth> models = new ArrayList<>();
        for (ModelSelection model : configured) {
            AiRun run = latest.get(model);
            models.add(new AiHealth.ModelHealth(model.provider(), model.model(),
                    run == null ? null : run.state().name(),
                    run == null || run.state() != RunState.FAILED ? null
                            : run.failure().map(failure -> failure.category().name()).orElse(null),
                    run == null ? null : run.finishedAt().orElse(null)));
        }
        return new AiHealth(anyProviderConfigured(), runs.lastSucceededAt().orElse(null), models);
    }

    private boolean anyProviderConfigured() {
        return gateways.values().stream().anyMatch(ModelGateway::configured);
    }

    private RunView view(AiRun run) {
        return RunViews.view(run, runs.steps(run.id()));
    }

    private AiRun load(UUID runId) {
        return load(runId, false);
    }

    private AiRun load(UUID runId, boolean forUpdate) {
        if (runId == null) {
            throw new NotFoundException("aiRun", "null");
        }
        RunId id = new RunId(runId);
        return (forUpdate ? runs.findByIdForUpdate(id) : runs.findById(id))
                .orElseThrow(() -> new NotFoundException("aiRun", runId.toString()));
    }

    private static Workload workload(String name) {
        try {
            return Workload.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidInputException("ai.workload.unknown", "Unknown AI workload",
                    Map.of("workload", String.valueOf(name)));
        }
    }
}
