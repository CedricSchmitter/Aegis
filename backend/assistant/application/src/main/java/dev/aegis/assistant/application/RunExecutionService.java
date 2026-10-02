package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.AiLock;
import dev.aegis.assistant.application.ports.EffectLedger;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotAuthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Transactional steps of run execution; wrapped by a transaction decorator in the composition root. */
public class RunExecutionService implements RunExecution {
    private final AiRunRepository runs;
    private final AiLock lock;
    private final EffectLedger ledger;
    private final RunScheduler scheduler;
    private final Map<Workload, WorkloadHandler> handlers;
    private final HouseholdAccess access;
    private final AiSettings settings;
    private final Clock clock;

    public RunExecutionService(AiRunRepository runs, AiLock lock, EffectLedger ledger, RunScheduler scheduler,
            Map<Workload, WorkloadHandler> handlers, HouseholdAccess access, AiSettings settings, Clock clock) {
        this.runs = runs;
        this.lock = lock;
        this.ledger = ledger;
        this.scheduler = scheduler;
        this.handlers = Map.copyOf(handlers);
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Begin begin(RunId id) {
        lock.exclusive();
        Optional<AiRun> found = runs.findByIdForUpdate(id);
        if (found.isEmpty()) {
            return new Finished(null);
        }
        AiRun run = found.get();
        Instant now = clock.instant();
        if (run.state().terminal()) {
            return new Finished(run.state());
        }
        if (run.state() == RunState.WAITING_RETRY && run.nextAttemptAt().filter(at -> at.isAfter(now)).isPresent()) {
            return new Later(run.nextAttemptAt().get());
        }
        if (!run.cancelRequested() && !slotFree(run, now)) {
            return new Later(now.plus(settings.slotWait()));
        }
        AiRun.StartResult start = run.start(now);
        switch (start) {
            case AiRun.Ended ended -> {
                runs.save(run);
                return new Finished(ended.state());
            }
            case AiRun.NotDue notDue -> {
                return new Later(notDue.at());
            }
            case AiRun.Started started -> {
                try {
                    access.requireMember(new Actor(run.requestedBy()));
                } catch (NotAuthorizedException notMember) {
                    run.fail(started.attempt(), FailureCategory.NOT_AUTHORIZED, "auth.forbidden",
                            "the requesting member is no longer an active household member", null, now);
                    runs.save(run);
                    return new Finished(run.state());
                }
                runs.save(run);
                return new Proceed(context(run, started.attempt()));
            }
        }
    }

    @Override
    public void offerFreeSlots() {
        lock.exclusive();
        Instant now = clock.instant();
        List<AiRun> waiting = runs.findAwaitingSlot(now);
        if (waiting.isEmpty()) {
            return;
        }
        List<AiRun> holders = slotHolders(waiting, now);
        for (AiRun candidate : waiting) {
            if (holders.stream().anyMatch(holder -> holder.id().equals(candidate.id())) || !fits(candidate, holders)) {
                continue;
            }
            // Locked like every other change of the run, so a concurrent cancellation is not overwritten. A run
            // without a job (restored data) would hold the slot without ever taking it up, so it gets no offer.
            Optional<AiRun> current = runs.findByIdForUpdate(candidate.id()).filter(run -> run.awaitsSlot(now));
            if (current.isPresent() && scheduler.wake(candidate.id(), now)) {
                current.get().offerSlot(now);
                runs.save(current.get());
                holders.add(current.get());
            }
        }
    }

    @Override
    public void wakeIfOffered(RunId id) {
        lock.exclusive();
        Instant now = clock.instant();
        if (runs.findById(id).filter(run -> run.holdsSlotOffer(now, settings.slotWait())).isPresent()) {
            scheduler.wake(id, now);
        }
    }

    private boolean slotFree(AiRun run, Instant now) {
        return fits(run, slotHolders(runs.findAwaitingSlot(now), now));
    }

    /** Runs holding a slot: running attempts and waiting runs with a valid offer. */
    private List<AiRun> slotHolders(List<AiRun> waiting, Instant now) {
        List<AiRun> holders = new ArrayList<>(
                runs.findRunning().stream().filter(run -> run.occupiesSlot(now)).toList());
        waiting.stream().filter(run -> run.holdsSlotOffer(now, settings.slotWait())).forEach(holders::add);
        return holders;
    }

    /** Whether {@code run} fits under the global and its workload's limit next to the other slot holders. */
    private boolean fits(AiRun run, List<AiRun> holders) {
        List<AiRun> others = holders.stream().filter(other -> !other.id().equals(run.id())).toList();
        if (others.size() >= settings.maxConcurrentRuns()) {
            return false;
        }
        int workloadLimit = settings.profile(run.workload()).map(WorkloadProfile::maxConcurrent).orElse(1);
        long sameWorkload = others.stream().filter(other -> other.workload() == run.workload()).count();
        return sameWorkload < workloadLimit;
    }

    @Override
    public AiRun.Checkpoint beforeModelCall(RunId id, int attempt, AiRun.CallQuote quote) {
        AiRun run = load(id);
        Instant now = clock.instant();
        AiRun.Checkpoint membership = requireActiveMember(run, attempt, now);
        if (membership != null) {
            return membership;
        }
        String provider = run.model().provider();
        Money spentToday = runs.costSince(settings.startOfDay(now), provider, settings.currency());
        AiRun.Checkpoint checkpoint = run.authorizeModelCall(attempt, quote,
                new AiRun.DailyBudget(settings.dailyCostLimit(provider), spentToday, settings.startOfNextDay(now)),
                now);
        saveIfChanged(run);
        return checkpoint;
    }

    @Override
    public AiRun.Checkpoint afterModelCall(RunId id, int attempt, String modelId, String outcome, TokenUsage usage,
            Money cost) {
        AiRun run = load(id);
        AiRun.Checkpoint checkpoint = run.recordModelCall(attempt, modelId, outcome, usage, cost, clock.instant());
        runs.save(run);
        return checkpoint;
    }

    @Override
    public AiRun.Checkpoint beforeToolCall(RunId id, int attempt, String toolName, boolean granted) {
        AiRun run = load(id);
        AiRun.Checkpoint checkpoint = run.authorizeToolCall(attempt, toolName, granted, clock.instant());
        saveIfChanged(run);
        return checkpoint;
    }

    @Override
    public AiRun.Checkpoint complete(RunId id, int attempt, ValidatedOutput output) {
        AiRun run = load(id);
        Instant now = clock.instant();
        AiRun.Checkpoint gate = run.readyToComplete(attempt, now);
        if (!(gate instanceof AiRun.Continue)) {
            saveIfChanged(run);
            return gate;
        }
        AiRun.Checkpoint membership = requireActiveMember(run, attempt, now);
        if (membership != null) {
            return membership;
        }
        WorkloadHandler handler = handlers.get(run.workload());
        RunContext context = context(run, attempt);
        if (!handler.sourcesCurrent(context)) {
            AiRun.Checkpoint failed = run.fail(attempt, FailureCategory.STALE_SOURCE, "source.changed",
                    "a source changed while the run was working; the result was not applied", null, now);
            runs.save(run);
            return failed;
        }
        Optional<String> effectKey = handler.effectKey(context, output);
        boolean apply = effectKey.map(key -> ledger.claim(key, run.id(), now)).orElse(true);
        if (apply) {
            handler.apply(context, output);
        }
        run.succeed(attempt, output.json(), !apply, now);
        runs.save(run);
        return gate;
    }

    @Override
    public AiRun.Checkpoint fail(RunId id, int attempt, FailureCategory category, String code, String message,
            Duration retryAfter) {
        AiRun run = load(id);
        AiRun.Checkpoint checkpoint = run.fail(attempt, category, code, message, retryAfter, clock.instant());
        saveIfChanged(run);
        return checkpoint;
    }

    RunContext context(AiRun run, int attempt) {
        // A model without effort levels (Haiku) rejects the parameter, so it is only sent to models that accept it.
        String effort = settings.catalog().acceptsEffort(run.model().model())
                ? settings.profile(run.workload()).map(WorkloadProfile::effort).orElse(null) : null;
        return new RunContext(run.id(), run.workload(), attempt, new Actor(run.requestedBy()), run.model(),
                run.sources(), run.parameters(), effort);
    }

    private void saveIfChanged(AiRun run) {
        if (!run.pendingSteps().isEmpty()) {
            runs.save(run);
        }
    }

    private AiRun.Checkpoint requireActiveMember(AiRun run, int attempt, Instant now) {
        try {
            access.requireMember(new Actor(run.requestedBy()));
            return null;
        } catch (NotAuthorizedException notMember) {
            AiRun.Checkpoint denied = run.fail(attempt, FailureCategory.NOT_AUTHORIZED, "auth.forbidden",
                    "the requesting member is no longer an active household member", null, now);
            runs.save(run);
            return denied;
        }
    }

    private AiRun load(RunId id) {
        return runs.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("AI run " + id + " vanished"));
    }
}
