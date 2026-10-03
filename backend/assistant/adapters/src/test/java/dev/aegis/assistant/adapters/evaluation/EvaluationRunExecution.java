package dev.aegis.assistant.adapters.evaluation;

import dev.aegis.assistant.application.RunContext;
import dev.aegis.assistant.application.RunExecution;
import dev.aegis.assistant.application.ValidatedOutput;
import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.shared.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The run steps of one chat run in memory, decided by the real {@link AiRun} aggregate: the same model call, tool
 * call, output and duration budgets as in the application, without database, job scheduler or slots. A retry is due
 * at once instead of after its delay, and the daily limit never binds, because neither is under evaluation.
 */
final class EvaluationRunExecution implements RunExecution {
    private final AiRun run;
    private final Actor actor;
    private final WorkloadHandler handler;
    private final Clock clock;
    private final AiRun.DailyBudget day;
    private Duration skipped = Duration.ZERO;

    EvaluationRunExecution(AiRun run, Actor actor, WorkloadHandler handler, Clock clock) {
        this.run = run;
        this.actor = actor;
        this.handler = handler;
        this.clock = clock;
        Money unbound = run.budget().maxCost().plus(run.budget().maxCost()).plus(run.budget().maxCost());
        this.day = new AiRun.DailyBudget(unbound, Money.zero(unbound.currency()), clock.instant().plus(
                Duration.ofDays(1)));
    }

    private Instant now() {
        return clock.instant().plus(skipped);
    }

    /** Lets the clock of this run jump to a retry that the aggregate scheduled for later. */
    void skipTo(Instant at) {
        Duration ahead = Duration.between(now(), at);
        if (ahead.isPositive()) {
            skipped = skipped.plus(ahead);
        }
    }

    AiRun run() {
        return run;
    }

    private RunContext context(int attempt) {
        return new RunContext(run.id(), run.workload(), attempt, actor, run.model(), run.sources(),
                run.parameters(), null);
    }

    @Override
    public Begin begin(RunId id) {
        return switch (run.start(now())) {
            case AiRun.Started started -> new Proceed(context(started.attempt()));
            case AiRun.NotDue notDue -> new Later(notDue.at());
            case AiRun.Ended ended -> new Finished(ended.state());
        };
    }

    @Override
    public void offerFreeSlots() {
    }

    @Override
    public void wakeIfOffered(RunId id) {
    }

    @Override
    public AiRun.Checkpoint beforeModelCall(RunId id, int attempt, AiRun.CallQuote quote) {
        return run.authorizeModelCall(attempt, quote, day, now());
    }

    @Override
    public AiRun.Checkpoint afterModelCall(RunId id, int attempt, String modelId, String outcome, TokenUsage usage,
            Money cost) {
        return run.recordModelCall(attempt, modelId, outcome, usage, cost, now());
    }

    @Override
    public AiRun.Checkpoint beforeToolCall(RunId id, int attempt, String toolName, boolean granted) {
        return run.authorizeToolCall(attempt, toolName, granted, now());
    }

    @Override
    public AiRun.Checkpoint complete(RunId id, int attempt, ValidatedOutput output) {
        AiRun.Checkpoint gate = run.readyToComplete(attempt, now());
        if (gate instanceof AiRun.Continue) {
            handler.apply(context(attempt), output);
            run.succeed(attempt, output.json(), false, now());
        }
        return gate;
    }

    @Override
    public AiRun.Checkpoint fail(RunId id, int attempt, FailureCategory category, String code, String message,
            Duration retryAfter) {
        return run.fail(attempt, category, code, message, retryAfter, now());
    }
}
