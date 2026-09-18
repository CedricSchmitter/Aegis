package dev.aegis.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.MemberId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiRunTest {
    static final Instant T0 = Instant.parse("2026-09-18T08:00:00Z");
    static final MemberId LISA = MemberId.random();
    static final Money DAY = Money.of("2.00", "USD");
    static final Money NOTHING = Money.of("0", "USD");
    /** Output price of 5 USD per million tokens: 1000 tokens cost 0.005. */
    static final AiRun.CallQuote QUOTE = new AiRun.CallQuote(Money.of("0.001", "USD"), Money.of("5", "USD"));

    static AiRun run(int maxAttempts) {
        RunBudget budget = new RunBudget(Duration.ofMinutes(2), Money.of("0.05", "USD"), 3, 2, 2048, maxAttempts);
        return AiRun.request(RunId.random(), Workload.DOCUMENT_CLASSIFICATION, "doc:1:v3", null, LISA, T0,
                new ModelSelection("anthropic", "claude-haiku-4-5"), budget,
                List.of(new SourceRef("document", "1", 3)), Map.of());
    }

    static AiRun started(int maxAttempts) {
        AiRun run = run(maxAttempts);
        assertThat(run.start(T0)).isEqualTo(new AiRun.Started(1));
        return run;
    }

    @Test
    void startsQueuedRunAsFirstAttempt() {
        AiRun run = run(3);

        AiRun.StartResult result = run.start(T0);

        assertThat(result).isEqualTo(new AiRun.Started(1));
        assertThat(run.state()).isEqualTo(RunState.RUNNING);
        assertThat(run.pendingSteps()).extracting(RunStep::kind).containsExactly(StepKind.ATTEMPT_STARTED);
    }

    @Test
    void retryableFailureWaitsWithBackoffAndIsNotDueEarly() {
        AiRun run = started(3);

        AiRun.Checkpoint halted = run.fail(1, FailureCategory.TIMEOUT, "provider.timeout", "slow", null, T0);

        assertThat(halted).isEqualTo(new AiRun.Halted(RunState.WAITING_RETRY, T0.plusSeconds(30)));
        assertThat(run.start(T0.plusSeconds(10))).isEqualTo(new AiRun.NotDue(T0.plusSeconds(30)));
        assertThat(run.start(T0.plusSeconds(30))).isEqualTo(new AiRun.Started(2));
        assertThat(run.failure()).get().extracting(RunFailure::category).isEqualTo(FailureCategory.TIMEOUT);
    }

    @Test
    void backoffGrowsHonorsRetryAfterAndIsCapped() {
        assertThat(AiRun.backoff(1, null)).isEqualTo(Duration.ofSeconds(30));
        assertThat(AiRun.backoff(3, null)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AiRun.backoff(12, null)).isEqualTo(Duration.ofMinutes(15));
        assertThat(AiRun.backoff(1, Duration.ofMinutes(3))).isEqualTo(Duration.ofMinutes(3));
        assertThat(AiRun.backoff(1, Duration.ofDays(1))).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void nonRetryableFailureEndsTheRun() {
        AiRun run = started(3);

        AiRun.Checkpoint halted = run.fail(1, FailureCategory.AUTHENTICATION, "provider.authentication", "401", null,
                T0);

        assertThat(halted).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.finishedAt()).contains(T0);
        assertThat(run.start(T0.plusSeconds(60))).isEqualTo(new AiRun.Ended(RunState.FAILED));
    }

    @Test
    void lastAttemptFailureEndsTheRunEvenIfRetryable() {
        AiRun run = started(1);

        assertThat(run.fail(1, FailureCategory.TIMEOUT, "provider.timeout", "slow", null, T0))
                .isEqualTo(new AiRun.Halted(RunState.FAILED, null));
    }

    @Test
    void interruptedAttemptIsRecordedAndRetriedAfterRestart() {
        AiRun run = started(2);

        AiRun.StartResult restarted = run.start(T0.plusSeconds(5));

        assertThat(restarted).isEqualTo(new AiRun.Started(2));
        assertThat(run.pendingSteps()).extracting(RunStep::kind).containsExactly(StepKind.ATTEMPT_STARTED,
                StepKind.ATTEMPT_FAILED, StepKind.ATTEMPT_STARTED);
        assertThat(run.failure()).get().extracting(RunFailure::category).isEqualTo(FailureCategory.INTERRUPTED);
    }

    @Test
    void interruptedLastAttemptFailsTheRun() {
        AiRun run = started(1);

        assertThat(run.start(T0.plusSeconds(5))).isEqualTo(new AiRun.Ended(RunState.FAILED));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("attempt.interrupted");
    }

    @Test
    void supersededAttemptCanNeitherCallNorComplete() {
        AiRun run = started(3);
        run.start(T0.plusSeconds(5));

        assertThat(run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0.plusSeconds(6)))
                .isInstanceOf(AiRun.Superseded.class);
        assertThat(run.authorizeToolCall(1, "lookup", true, T0.plusSeconds(6))).isInstanceOf(AiRun.Superseded.class);
        assertThat(run.readyToComplete(1, T0.plusSeconds(6))).isInstanceOf(AiRun.Superseded.class);
        assertThat(run.fail(1, FailureCategory.TIMEOUT, "x", "y", null, T0)).isInstanceOf(AiRun.Superseded.class);
        assertThatThrownBy(() -> run.succeed(1, "{}", false, T0.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.state()).isEqualTo(RunState.RUNNING);
        assertThat(run.attempt()).isEqualTo(2);
    }

    @Test
    void costOfSupersededCallIsStillRecorded() {
        AiRun run = started(3);
        run.start(T0.plusSeconds(5));

        AiRun.Checkpoint checkpoint = run.recordModelCall(1, "claude-haiku-4-5", "stop.end",
                new TokenUsage(100, 10, 0, 0), Money.of("0.0002", "USD"), T0.plusSeconds(6));

        assertThat(checkpoint).isInstanceOf(AiRun.Superseded.class);
        assertThat(run.cost()).isEqualTo(Money.of("0.0002", "USD"));
        assertThat(run.modelCalls()).isEqualTo(1);
        assertThat(run.attemptModelCalls()).isZero();
    }

    @Test
    void outputAllowanceShrinksToWhatTheBudgetCanPay() {
        AiRun run = started(3);
        run.recordModelCall(1, "m", "stop.tool_use", new TokenUsage(1000, 100, 0, 0), Money.of("0.045", "USD"), T0);

        AiRun.Checkpoint allowance = run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0.plusSeconds(1));

        // 0.05 - 0.045 - 0.001 input = 0.004 USD -> 800 output tokens at 5 USD per million.
        assertThat(allowance).isEqualTo(new AiRun.Allowed(800, Duration.ofSeconds(119)));
    }

    @Test
    void callThatCannotBePaidExhaustsTheCostBudget() {
        AiRun run = started(3);
        run.recordModelCall(1, "m", "stop.tool_use", new TokenUsage(1000, 100, 0, 0), Money.of("0.0495", "USD"), T0);

        AiRun.Checkpoint allowance = run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0.plusSeconds(1));

        assertThat(allowance).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("budget.cost");
    }

    @Test
    void dailyLimitStopsCallsAcrossRuns() {
        AiRun run = started(3);

        AiRun.Checkpoint allowance = run.authorizeModelCall(1, QUOTE, Money.of("1.9995", "USD"), DAY, T0);

        assertThat(allowance).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::category, RunFailure::code)
                .containsExactly(FailureCategory.BUDGET_EXHAUSTED, "budget.daily");
    }

    @Test
    void reportedCostAboveTheBudgetFailsWithoutRetry() {
        AiRun run = started(3);

        AiRun.Checkpoint checkpoint = run.recordModelCall(1, "m", "stop.end", new TokenUsage(10, 10, 0, 0),
                Money.of("0.06", "USD"), T0);

        assertThat(checkpoint).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("budget.cost");
    }

    @Test
    void modelCallBudgetIsPerAttempt() {
        AiRun run = started(3);
        for (int i = 0; i < 3; i++) {
            assertThat(run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0)).isInstanceOf(AiRun.Allowed.class);
            run.recordModelCall(1, "m", "stop.tool_use", TokenUsage.NONE, Money.of("0.0001", "USD"), T0);
        }

        assertThat(run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0))
                .isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("budget.modelCalls");
    }

    @Test
    void timeBudgetEndsTheAttempt() {
        AiRun run = started(3);

        assertThat(run.authorizeModelCall(1, QUOTE, NOTHING, DAY, T0.plus(Duration.ofMinutes(2))))
                .isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("budget.time");
    }

    @Test
    void toolCallBudgetAndDeniedToolsAreEnforced() {
        AiRun run = started(3);

        assertThat(run.authorizeToolCall(1, "lookup", true, T0)).isInstanceOf(AiRun.Continue.class);
        assertThat(run.authorizeToolCall(1, "shell", false, T0)).isEqualTo(new AiRun.Denied("tool.notGranted"));
        assertThat(run.authorizeToolCall(1, "lookup", true, T0)).isInstanceOf(AiRun.Continue.class);
        assertThat(run.authorizeToolCall(1, "lookup", true, T0)).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::code).isEqualTo("budget.toolCalls");
        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(run.deniedToolCalls()).isEqualTo(1);
    }

    @Test
    void repeatedRequestsForUngrantedToolsStopTheRun() {
        AiRun run = started(3);

        run.authorizeToolCall(1, "shell", false, T0);
        run.authorizeToolCall(1, "delete_all_documents", false, T0);
        AiRun.Checkpoint third = run.authorizeToolCall(1, "read_secrets", false, T0);

        assertThat(third).isEqualTo(new AiRun.Halted(RunState.FAILED, null));
        assertThat(run.failure()).get().extracting(RunFailure::category).isEqualTo(FailureCategory.TOOL_DENIED);
    }

    @Test
    void cancellingAWaitingRunEndsItImmediately() {
        AiRun queued = run(3);

        assertThat(queued.requestCancel(LISA, T0)).isTrue();

        assertThat(queued.state()).isEqualTo(RunState.CANCELLED);
        assertThat(queued.start(T0)).isEqualTo(new AiRun.Ended(RunState.CANCELLED));
    }

    @Test
    void cancellingARunningAttemptTakesEffectAtTheNextCheckpointAndBeforeCompletion() {
        AiRun run = started(3);

        assertThat(run.requestCancel(LISA, T0)).isTrue();
        assertThat(run.state()).isEqualTo(RunState.RUNNING);
        AiRun.Checkpoint gate = run.readyToComplete(1, T0.plusSeconds(1));

        assertThat(gate).isEqualTo(new AiRun.Halted(RunState.CANCELLED, null));
        assertThat(run.output()).isEmpty();
        assertThat(run.cancelRequestedBy()).contains(LISA);
    }

    @Test
    void cancelledRunReportsCostOfAnInFlightCall() {
        AiRun run = started(3);
        run.requestCancel(LISA, T0);

        AiRun.Checkpoint checkpoint = run.recordModelCall(1, "m", "stop.end", new TokenUsage(10, 10, 0, 0),
                Money.of("0.001", "USD"), T0.plusSeconds(1));

        assertThat(checkpoint).isEqualTo(new AiRun.Halted(RunState.CANCELLED, null));
        assertThat(run.cost()).isEqualTo(Money.of("0.001", "USD"));
    }

    @Test
    void terminalRunIgnoresCancellationAndKeepsItsResult() {
        AiRun run = started(3);
        run.succeed(1, "{\"label\":\"invoice\"}", false, T0);

        assertThat(run.requestCancel(LISA, T0)).isFalse();
        assertThat(run.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(run.output()).contains("{\"label\":\"invoice\"}");
    }

    @Test
    void onlyRunningAttemptsWithinTheirTimeBudgetOccupySlots() {
        AiRun run = started(3);

        assertThat(run.occupiesSlot(T0.plusSeconds(60))).isTrue();
        assertThat(run.occupiesSlot(T0.plus(Duration.ofMinutes(3)))).isFalse();
        assertThat(run(3).occupiesSlot(T0)).isFalse();
    }

    @Test
    void persistedClearsPendingStepsAndAdvancesVersionOfStoredRuns() {
        AiRun run = run(3);
        run.start(T0);
        run.persisted();
        assertThat(run.version()).isZero();
        assertThat(run.pendingSteps()).isEmpty();

        run.requestCancel(LISA, T0);
        run.persisted();

        assertThat(run.version()).isEqualTo(1);
    }
}
