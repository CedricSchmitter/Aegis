package dev.aegis.assistant.application;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.assistant.application.api.AiModelCommands;
import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.assistant.application.api.AiRunStatusQuery;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.ModelOptionView;
import dev.aegis.assistant.application.api.RequestOutcome;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.WorkloadEstimate;
import dev.aegis.assistant.application.api.WorkloadView;
import dev.aegis.assistant.application.ports.AiLock;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunBudget;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadModelChoice;
import dev.aegis.assistant.domain.WorkloadModelChoiceRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Member-facing AI use cases: requesting, inspecting, cancelling and retrying runs, the connection check and the
 * household's model choice. Every entry point checks household membership. A run uses the model of the household's
 * family for its workload with the first provider of the profile that has credentials and serves the requesting
 * member; provider and model are recorded with the run, so a later choice affects new runs only. Requests that cannot
 * run (no credentials, provider not available to the member, no handler, unknown price) are refused instead of
 * queued. Wrapped in a transaction by the composition root; no call waits on a model.
 */
public class AiRunService implements RequestAiRun, AiRunQueries, AiRunCommands, AiHealthQuery, AiRunStatusQuery,
        AiModelCommands {
    public static final int MAX_LIST = 100;
    /** Succeeded runs whose mean cost estimates the next one. */
    static final int ESTIMATE_SAMPLES = 20;
    /** Codes of requests that cannot run with the current configuration or membership. */
    static final String MEMBER_NOT_ALLOWED = "ai.provider.memberNotAllowed";
    static final Set<String> UNAVAILABLE_CODES = Set.of("ai.notConfigured", "ai.workload.unavailable",
            "ai.price.unknown", MEMBER_NOT_ALLOWED);

    private final AiRunRepository runs;
    private final WorkloadModelChoiceRepository choices;
    private final RunScheduler scheduler;
    private final AiLock lock;
    private final Map<Workload, WorkloadHandler> handlers;
    private final Map<String, ModelGateway> gateways;
    private final AiSettings settings;
    private final HouseholdAccess access;
    private final Clock clock;
    private final Map<ModelSelection, CredentialState> credentialStates = new LinkedHashMap<>();
    private final Instant serviceStartedAt;
    private Set<ModelSelection> lastHealthSelection = Set.of();

    private record CredentialState(String revision, Instant freshSince) {
    }

    public AiRunService(AiRunRepository runs, WorkloadModelChoiceRepository choices, RunScheduler scheduler,
            AiLock lock, Map<Workload, WorkloadHandler> handlers, Map<String, ModelGateway> gateways,
            AiSettings settings, HouseholdAccess access, Clock clock) {
        this.runs = runs;
        this.choices = choices;
        this.scheduler = scheduler;
        this.lock = lock;
        this.handlers = Map.copyOf(handlers);
        this.gateways = Map.copyOf(gateways);
        this.settings = settings;
        this.access = access;
        this.clock = clock;
        this.serviceStartedAt = clock.instant();
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
        ModelSelection model = requireRunnable(actor, workload, candidates(profile));
        List<SourceRef> sources = new ArrayList<>();
        for (SourceVersion source : request.sources()) {
            try {
                sources.add(new SourceRef(source.kind(), source.id(), source.version()));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new InvalidInputException("validation.failed", "Invalid run source");
            }
        }
        return view(create(actor, workload, request.idempotencyKey(), null, model, settings.budget(profile, model.provider()), sources,
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
        boolean refusedForMember = false;
        for (Workload workload : Workload.values()) {
            Optional<WorkloadProfile> profile = settings.profile(workload);
            if (profile.isEmpty()) {
                continue;
            }
            List<ModelSelection> candidates = candidates(profile.get());
            Optional<ModelSelection> target = first(candidates, model -> usable(model, actor) && priced(model));
            target.ifPresent(model -> targets.computeIfAbsent(model, key -> new LinkedHashSet<>())
                    .add(workload.name()));
            refusedForMember |= target.isEmpty()
                    && first(candidates, model -> providerConfigured(model) && priced(model)).isPresent();
        }
        if (!handlers.containsKey(Workload.CONNECTION_CHECK) || targets.isEmpty() && !refusedForMember) {
            throw new InvalidInputException("ai.notConfigured",
                    "No AI provider with credentials and a priced model is configured");
        }
        if (targets.isEmpty()) {
            throw memberNotAllowed();
        }
        List<RunView> started = new ArrayList<>();
        targets.forEach((model, workloads) -> started.add(view(create(actor, Workload.CONNECTION_CHECK,
                "connection-check:" + model.provider() + ":" + model.model() + ":" + String.join(",", workloads), null,
                model, settings.budget(check, model.provider()), List.of(),
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
        ModelSelection model = requireRunnable(actor, previous.workload(), retryCandidates(previous));
        return view(create(actor, previous.workload(), previous.idempotencyKey(), previous.id(), model,
                settings.profile(previous.workload()).map(profile -> settings.budget(profile, model.provider()))
                        .orElse(previous.budget()), previous.sources(), previous.parameters(), true));
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

    /** Chooses the first candidate the member may use now, or explains why none can run. */
    private ModelSelection requireRunnable(Actor actor, Workload workload, List<ModelSelection> candidates) {
        if (!handlers.containsKey(workload)) {
            throw new InvalidInputException("ai.workload.unavailable",
                    "Workload " + workload + " has no implemented workflow yet");
        }
        Optional<ModelSelection> chosen = candidates.stream().filter(model -> usable(model, actor)).findFirst();
        if (chosen.isEmpty()) {
            if (candidates.stream().anyMatch(this::providerConfigured)) {
                throw memberNotAllowed();
            }
            throw new InvalidInputException("ai.notConfigured",
                    "No provider of " + workload + " has server-side credentials");
        }
        ModelSelection model = chosen.get();
        if (!priced(model)) {
            throw new InvalidInputException("ai.price.unknown", "No price is configured for " + model.model());
        }
        return model;
    }

    /**
     * A retry keeps the model of the previous run and chooses its provider again by the current preference, so a
     * run that failed without an API key can be retried on the key once it is installed, and vice versa. A connection
     * check keeps its provider because it checks exactly that provider.
     */
    private List<ModelSelection> retryCandidates(AiRun previous) {
        ModelSelection model = previous.model();
        if (previous.workload() == Workload.CONNECTION_CHECK) {
            return List.of(model);
        }
        List<ModelSelection> candidates = settings.profile(previous.workload()).map(WorkloadProfile::providers)
                .orElse(List.of()).stream().map(provider -> new ModelSelection(provider, model.model())).toList();
        return candidates.isEmpty() ? List.of(model) : candidates;
    }

    private static InvalidInputException memberNotAllowed() {
        return new InvalidInputException(MEMBER_NOT_ALLOWED,
                "The configured AI provider is not available for this member");
    }

    private boolean usable(ModelSelection model, Actor actor) {
        ModelGateway gateway = gateways.get(model.provider());
        return gateway != null && gateway.configured() && gateway.allows(actor.memberId());
    }

    private boolean providerConfigured(ModelSelection model) {
        ModelGateway gateway = gateways.get(model.provider());
        return gateway != null && gateway.configured();
    }

    private boolean priced(ModelSelection model) {
        return settings.prices().find(model.model()).isPresent();
    }

    private static Optional<ModelSelection> first(List<ModelSelection> candidates, Predicate<ModelSelection> accepted) {
        return candidates.stream().filter(accepted).findFirst();
    }

    // Model choice --------------------------------------------------------------------------------------------------

    @Override
    public AiOverview chooseModel(Actor actor, String workload, String family, long version) {
        access.requireMember(actor);
        Workload target = workload(workload);
        WorkloadProfile profile = settings.profile(target).filter(candidate -> !candidate.providers().isEmpty())
                .orElseThrow(() -> new InvalidInputException("ai.model.notSelectable",
                        "Workload " + target + " has no model of its own", Map.of("workload", target.name())));
        ModelFamily chosen;
        try {
            chosen = ModelFamily.fromKey(family);
        } catch (IllegalArgumentException unknown) {
            throw new InvalidInputException("ai.model.unknownFamily", "Unknown model family",
                    Map.of("family", String.valueOf(family)));
        }
        if (settings.catalog().entry(chosen).isEmpty()) {
            throw new InvalidInputException("ai.model.notOffered", "The model family is not offered",
                    Map.of("family", chosen.key()));
        }
        WorkloadModelChoice choice = choice(profile);
        if (choice.choose(chosen, version, actor.memberId(), clock.instant())) {
            choices.save(choice);
        }
        return overview(actor);
    }

    /** The household's choice for a profile, or its configured default while nobody has changed it. */
    private WorkloadModelChoice choice(WorkloadProfile profile) {
        return choices.find(profile.workload())
                .orElseGet(() -> WorkloadModelChoice.defaultOf(profile.workload(), profile.family()));
    }

    /**
     * The profile's providers in order of preference with the catalog model of the household's family. A family that
     * the catalog no longer offers falls back to the profile's default family.
     */
    private List<ModelSelection> candidates(WorkloadProfile profile) {
        if (profile.providers().isEmpty()) {
            return List.of();
        }
        String model = settings.catalog().entry(choice(profile).family())
                .or(() -> settings.catalog().entry(profile.family())).map(ModelCatalog.Entry::model).orElseThrow();
        return profile.providers().stream().map(provider -> new ModelSelection(provider, model)).toList();
    }

    private List<ModelOptionView> modelOptions() {
        return settings.catalog().offered().stream().map(entry -> {
            Optional<ModelPrice> price = settings.prices().find(entry.model());
            return new ModelOptionView(entry.family().key(), entry.model(),
                    price.map(known -> known.inputPerMillion().amount()).orElse(null),
                    price.map(known -> known.outputPerMillion().amount()).orElse(null));
        }).toList();
    }

    // Queries -------------------------------------------------------------------------------------------------------

    @Override
    public AiOverview overview(Actor actor) {
        access.requireMember(actor);
        Instant now = clock.instant();
        boolean configured = anyProviderConfigured();
        List<WorkloadView> workloads = new ArrayList<>();
        // Limits and spending are shown for the provider this member's runs use now, otherwise the first shown one.
        String usableProvider = null;
        String shownProvider = null;
        for (Workload workload : Workload.values()) {
            Optional<WorkloadProfile> profile = settings.profile(workload);
            if (profile.isPresent()) {
                WorkloadView view = workloadView(profile.get(), actor);
                workloads.add(view);
                shownProvider = shownProvider == null ? view.provider() : shownProvider;
                Optional<ModelSelection> usable = first(candidates(profile.get()), model -> usable(model, actor));
                if (usableProvider == null && usable.isPresent()) {
                    usableProvider = usable.get().provider();
                }
            }
        }
        boolean memberAllowed = usableProvider != null;
        String limitProvider = memberAllowed ? usableProvider : shownProvider;
        return new AiOverview(configured, memberAllowed, limitProvider,
                settings.dailyCostLimit(limitProvider).amount(),
                limitProvider == null ? BigDecimal.ZERO
                        : runs.costSince(settings.startOfDay(now), limitProvider, settings.currency()).amount(),
                settings.currency().getCurrencyCode(), settings.maxConcurrentRuns(), workloads, modelOptions());
    }

    /** Shows the candidate a run of this member would use now, otherwise the configured or most preferred one. */
    private WorkloadView workloadView(WorkloadProfile profile, Actor actor) {
        List<ModelSelection> candidates = candidates(profile);
        ModelSelection model = first(candidates, candidate -> usable(candidate, actor))
                .or(() -> first(candidates, this::providerConfigured)).or(() -> candidates.stream().findFirst())
                .orElse(null);
        WorkloadModelChoice choice = profile.providers().isEmpty() ? null : choice(profile);
        boolean handler = handlers.containsKey(profile.workload());
        boolean provider = model == null ? anyProviderConfigured() : providerConfigured(model);
        boolean allowed = model == null
                ? gateways.values().stream().anyMatch(gateway -> gateway.configured()
                        && gateway.allows(actor.memberId()))
                : usable(model, actor);
        boolean priced = model == null || priced(model);
        RunBudget budget = model == null ? profile.budget() : settings.budget(profile, model.provider());
        return new WorkloadView(profile.workload().name(), model == null ? null : model.provider(),
                model == null ? null : model.model(), choice == null ? null : choice.family().key(),
                choice == null ? 0 : choice.version(),
                choice == null ? null : choice.changedBy().map(MemberId::value).orElse(null),
                choice == null ? null : choice.changedAt().orElse(null), provider, handler,
                handler && provider && allowed && priced,
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
    public WorkloadEstimate estimate(Actor actor, String workloadName) {
        access.requireMember(actor);
        Workload workload = workload(workloadName);
        Instant now = clock.instant();
        String currency = settings.currency().getCurrencyCode();
        Optional<WorkloadProfile> profile = settings.profile(workload);
        if (profile.isEmpty() || profile.get().providers().isEmpty()) {
            return new WorkloadEstimate(workload.name(), "ai.workload.unavailable", null, null, null, 0, null, null,
                    BigDecimal.ZERO, currency, settings.startOfNextDay(now));
        }
        List<ModelSelection> candidates = candidates(profile.get());
        String refusal = null;
        ModelSelection model;
        try {
            model = requireRunnable(actor, workload, candidates);
        } catch (InvalidInputException refused) {
            refusal = refused.code();
            model = first(candidates, this::providerConfigured).orElse(candidates.getFirst());
        }
        AiRunRepository.CostSample sample = runs.recentSucceededCost(workload, model.model(), settings.currency(),
                ESTIMATE_SAMPLES);
        return new WorkloadEstimate(workload.name(), refusal, model.provider(), model.model(),
                sample.average() == null ? null : sample.average().amount(), sample.runs(),
                settings.budget(profile.get(), model.provider()).maxCost().amount(),
                settings.dailyCostLimit(model.provider()).amount(),
                runs.costSince(settings.startOfDay(now), model.provider(), settings.currency()).amount(), currency,
                settings.startOfNextDay(now));
    }

    @Override
    public RunView get(Actor actor, UUID runId) {
        access.requireMember(actor);
        return view(load(runId));
    }

    /**
     * Health per model currently in use: for each profile the first candidate with credentials (otherwise the most
     * preferred one) with its latest succeeded or failed run of any workload. Models no longer in use do not affect
     * the status.
     */
    @Override
    public synchronized AiHealth health(Actor actor) {
        access.requireMember(actor);
        Map<ModelSelection, AiRun> latest = new LinkedHashMap<>();
        for (AiRun run : runs.findLatestOutcomePerModel()) {
            latest.put(run.model(), run);
        }
        Set<ModelSelection> configured = new LinkedHashSet<>();
        for (Workload workload : Workload.values()) {
            settings.profile(workload).map(this::candidates).flatMap(candidates ->
                    first(candidates, this::providerConfigured).or(() -> candidates.stream().findFirst()))
                    .ifPresent(configured::add);
        }
        List<AiHealth.ModelHealth> models = new ArrayList<>();
        for (ModelSelection model : configured) {
            ModelGateway gateway = gateways.get(model.provider());
            boolean credentialsAvailable = gateway != null && gateway.configured();
            String revision = gateway == null ? "missing" : gateway.credentialRevision();
            boolean returnedToSelection = !lastHealthSelection.isEmpty() && !lastHealthSelection.contains(model);
            CredentialState state = credentialStates.compute(model, (key, existing) -> existing == null
                    ? new CredentialState(revision, serviceStartedAt)
                    : !existing.revision().equals(revision) || returnedToSelection
                            ? new CredentialState(revision, clock.instant()) : existing);
            AiRun run = latest.get(model);
            if (run != null && run.finishedAt().orElse(Instant.MIN).isBefore(state.freshSince())) {
                run = null;
            }
            models.add(new AiHealth.ModelHealth(model.provider(), model.model(), credentialsAvailable,
                    run == null ? null : run.state().name(),
                    run == null || run.state() != RunState.FAILED ? null
                            : run.failure().map(failure -> failure.category().name()).orElse(null),
                    run == null ? null : run.finishedAt().orElse(null)));
        }
        lastHealthSelection = Set.copyOf(configured);
        Instant lastSuccess = models.stream().filter(model -> "SUCCEEDED".equals(model.lastState()))
                .map(AiHealth.ModelHealth::lastFinishedAt).max(Instant::compareTo).orElse(null);
        return new AiHealth(anyProviderConfigured(), lastSuccess, models);
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
