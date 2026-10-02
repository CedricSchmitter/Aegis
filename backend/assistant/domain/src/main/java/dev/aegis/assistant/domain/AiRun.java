package dev.aegis.assistant.domain;

import dev.aegis.shared.MemberId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One bounded AI run. The aggregate owns the visible state machine and every budget decision, so the executor, the
 * job scheduler and member commands cannot bypass them:
 *
 * <ul>
 * <li>Only the current attempt of a {@code RUNNING} run may call the model, call tools or complete; an executor that
 * lost its attempt (restart, revived job) is told it was superseded and must not write anything.</li>
 * <li>The cost budget spans all attempts. Before each model call the output allowance is reduced so that the
 * worst-case cost stays within the remaining run and daily budgets; the reported cost is checked again after the
 * call.</li>
 * <li>An attempt stopped only by the household's daily limit waits for the next budget day instead of failing, as
 * long as a fresh day could pay the call. Such a wait does not use up one of the run's attempts; the cost budget still
 * spans all of them.</li>
 * <li>Cancellation of a running attempt takes effect at the next checkpoint and always before a result is applied.
 * </li>
 * <li>A result is accepted once; a terminal run never changes state again.</li>
 * <li>A slot offered to a waiting run is reserved for it until its next attempt starts, it ends or the offer lapses.
 * </li>
 * </ul>
 */
public final class AiRun {
    /** A running attempt older than its time budget plus this grace is presumed dead and frees its slot. */
    public static final Duration SLOT_GRACE = Duration.ofMinutes(1);
    /** Calls to tools outside the workload grant that are tolerated before the run is stopped. */
    public static final int MAX_DENIED_TOOL_CALLS = 3;
    /** Smallest output allowance worth a model call; below it the budget counts as exhausted. */
    public static final int MIN_OUTPUT_TOKENS = 64;
    static final Duration RETRY_BASE = Duration.ofSeconds(30);
    static final Duration RETRY_MAX = Duration.ofMinutes(15);
    static final Duration RETRY_AFTER_MAX = Duration.ofHours(1);
    private static final int MAX_NAME = 100;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** Outcome of a checkpoint for the executor holding {@code attempt}. */
    public sealed interface Checkpoint permits Continue, Allowed, Denied, Halted, Superseded {
    }

    /** The attempt may go on. */
    public record Continue() implements Checkpoint {
    }

    /** A model call may start with at most this many output tokens and must finish within {@code remaining}. */
    public record Allowed(int maxOutputTokens, Duration remaining) implements Checkpoint {
    }

    /** The tool call is refused; the attempt continues and the model receives an error result. */
    public record Denied(String code) implements Checkpoint {
    }

    /** The attempt ended; the run is now waiting for a retry, failed or cancelled. */
    public record Halted(RunState state, Instant nextAttemptAt) implements Checkpoint {
    }

    /** The executor's attempt is no longer current; it must stop without writing. */
    public record Superseded() implements Checkpoint {
    }

    /** Result of starting a job execution. */
    public sealed interface StartResult permits Started, NotDue, Ended {
    }

    public record Started(int attempt) implements StartResult {
    }

    public record NotDue(Instant at) implements StartResult {
    }

    public record Ended(RunState state) implements StartResult {
    }

    /** Estimated input cost of the next call and the output price per million tokens of the model. */
    public record CallQuote(Money inputCost, Money outputPricePerMillion) {
        public CallQuote {
            Objects.requireNonNull(inputCost, "input cost");
            Objects.requireNonNull(outputPricePerMillion, "output price");
        }
    }

    /**
     * The household's daily limit for the run's provider, what its model calls spent so far on the current budget
     * day, and when the next budget day starts.
     */
    public record DailyBudget(Money limit, Money spent, Instant renewsAt) {
        public DailyBudget {
            Objects.requireNonNull(limit, "daily limit");
            Objects.requireNonNull(spent, "spent today");
            Objects.requireNonNull(renewsAt, "renewal");
        }

        Money remaining() {
            return limit.minus(spent);
        }
    }

    /** Failure code of an attempt stopped by the daily limit; the run waits for the next budget day. */
    public static final String DAILY_LIMIT = "budget.daily";

    private final RunId id;
    private final Workload workload;
    private final String idempotencyKey;
    private final RunId retryOf;
    private final MemberId requestedBy;
    private final Instant requestedAt;
    private final ModelSelection model;
    private final RunBudget budget;
    private final List<SourceRef> sources;
    private final Map<String, String> parameters;

    private RunState state;
    private int attempt;
    private Instant attemptStartedAt;
    private Instant nextAttemptAt;
    private Instant finishedAt;
    private boolean cancelRequested;
    private MemberId cancelRequestedBy;
    private int modelCalls;
    private int toolCalls;
    private int deniedToolCalls;
    private int attemptModelCalls;
    private int attemptToolCalls;
    private TokenUsage tokens;
    private Money cost;
    private String output;
    private RunFailure failure;
    private long version;
    private boolean fresh;
    private int stepCount;
    private int budgetWaits;
    private Instant slotOfferedAt;
    private final List<RunStep> pendingSteps = new ArrayList<>();

    private AiRun(RunId id, Workload workload, String idempotencyKey, RunId retryOf, MemberId requestedBy,
            Instant requestedAt, ModelSelection model, RunBudget budget, List<SourceRef> sources,
            Map<String, String> parameters) {
        this.id = Objects.requireNonNull(id, "id");
        this.workload = Objects.requireNonNull(workload, "workload");
        this.idempotencyKey = requireKey(idempotencyKey);
        this.retryOf = retryOf;
        this.requestedBy = Objects.requireNonNull(requestedBy, "requested by");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requested at");
        this.model = Objects.requireNonNull(model, "model");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.sources = List.copyOf(sources == null ? List.of() : sources);
        this.parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    }

    /** A new run waiting for its first attempt. */
    public static AiRun request(RunId id, Workload workload, String idempotencyKey, RunId retryOf,
            MemberId requestedBy, Instant requestedAt, ModelSelection model, RunBudget budget,
            List<SourceRef> sources, Map<String, String> parameters) {
        AiRun run = new AiRun(id, workload, idempotencyKey, retryOf, requestedBy, requestedAt, model, budget, sources,
                parameters);
        run.state = RunState.QUEUED;
        run.tokens = TokenUsage.NONE;
        run.cost = Money.zero(budget.maxCost().currency());
        run.fresh = true;
        return run;
    }

    /**
     * Persisted state; counters and usage are the stored totals. {@code budgetWaits} counts the attempts that ended
     * only because the daily limit was reached; {@code slotOfferedAt} is when a freed slot was last offered to the
     * waiting run.
     */
    public record Snapshot(RunState state, int attempt, Instant attemptStartedAt, Instant nextAttemptAt,
            Instant finishedAt, boolean cancelRequested, MemberId cancelRequestedBy, int modelCalls, int toolCalls,
            int deniedToolCalls, int attemptModelCalls, int attemptToolCalls, TokenUsage tokens, Money cost,
            String output, RunFailure failure, long version, int stepCount, int budgetWaits, Instant slotOfferedAt) {
    }

    public static AiRun rehydrate(RunId id, Workload workload, String idempotencyKey, RunId retryOf,
            MemberId requestedBy, Instant requestedAt, ModelSelection model, RunBudget budget,
            List<SourceRef> sources, Map<String, String> parameters, Snapshot snapshot) {
        AiRun run = new AiRun(id, workload, idempotencyKey, retryOf, requestedBy, requestedAt, model, budget, sources,
                parameters);
        run.state = snapshot.state();
        run.attempt = snapshot.attempt();
        run.attemptStartedAt = snapshot.attemptStartedAt();
        run.nextAttemptAt = snapshot.nextAttemptAt();
        run.finishedAt = snapshot.finishedAt();
        run.cancelRequested = snapshot.cancelRequested();
        run.cancelRequestedBy = snapshot.cancelRequestedBy();
        run.modelCalls = snapshot.modelCalls();
        run.toolCalls = snapshot.toolCalls();
        run.deniedToolCalls = snapshot.deniedToolCalls();
        run.attemptModelCalls = snapshot.attemptModelCalls();
        run.attemptToolCalls = snapshot.attemptToolCalls();
        run.tokens = snapshot.tokens();
        run.cost = snapshot.cost();
        run.output = snapshot.output();
        run.failure = snapshot.failure();
        run.version = snapshot.version();
        run.stepCount = snapshot.stepCount();
        run.budgetWaits = snapshot.budgetWaits();
        run.slotOfferedAt = snapshot.slotOfferedAt();
        run.fresh = false;
        return run;
    }

    /**
     * Starts the next attempt when due. A run found {@code RUNNING} belongs to an attempt that was interrupted by a
     * restart; it is recorded as such and retried within the attempt and cost budgets.
     */
    public StartResult start(Instant now) {
        if (state.terminal()) {
            return new Ended(state);
        }
        if (cancelRequested) {
            cancel(now);
            return new Ended(state);
        }
        if (state == RunState.WAITING_RETRY && nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
            return new NotDue(nextAttemptAt);
        }
        if (state == RunState.RUNNING) {
            recordFailure(FailureCategory.INTERRUPTED, "attempt.interrupted",
                    "attempt " + attempt + " did not finish; the process stopped or the job was revived", now);
            if (attemptsUsed() >= budget.maxAttempts()) {
                finish(RunState.FAILED, now);
                return new Ended(state);
            }
        }
        if (cost.compareTo(budget.maxCost()) >= 0) {
            recordFailure(FailureCategory.BUDGET_EXHAUSTED, "budget.cost", "cost budget of this run is used up", now);
            finish(RunState.FAILED, now);
            return new Ended(state);
        }
        state = RunState.RUNNING;
        attempt++;
        attemptStartedAt = now;
        nextAttemptAt = null;
        slotOfferedAt = null;
        attemptModelCalls = 0;
        attemptToolCalls = 0;
        step(StepKind.ATTEMPT_STARTED, model.model(), "attempt." + attempt, null, null, now);
        return new Started(attempt);
    }

    /**
     * Admits the next model call of {@code forAttempt} or stops the attempt. The output allowance is the smaller of
     * the per-call cap and what the remaining run and daily budgets can pay after the estimated input cost.
     *
     * <p>When only the daily limit stands in the way and the full limit of a fresh day could pay the call, the run
     * waits for {@link DailyBudget#renewsAt()} without using up an attempt. Otherwise waiting would not
     * help, and the run fails: with {@code budget.cost} when its own budget is used up, with {@code budget.daily} when
     * the daily limit is too small for a single call.
     */
    public Checkpoint authorizeModelCall(int forAttempt, CallQuote quote, DailyBudget day, Instant now) {
        Optional<Checkpoint> stop = interruptions(forAttempt, now);
        if (stop.isPresent()) {
            return stop.get();
        }
        if (attemptModelCalls >= budget.maxModelCalls()) {
            return failAttempt(FailureCategory.BUDGET_EXHAUSTED, "budget.modelCalls",
                    "model call budget of " + budget.maxModelCalls() + " per attempt reached", null, now);
        }
        Money remainingRun = budget.maxCost().minus(cost);
        boolean dailyLimits = day.remaining().compareTo(remainingRun) < 0;
        int allowance = affordableTokens(remainingRun.min(day.remaining()).minus(quote.inputCost()),
                quote.outputPricePerMillion());
        if (allowance >= minimumAllowance()) {
            return new Allowed(allowance, remaining(now));
        }
        if (!dailyLimits) {
            return failAttempt(FailureCategory.BUDGET_EXHAUSTED, "budget.cost", "cost budget of this run is used up",
                    null, now);
        }
        int freshDayAllowance = affordableTokens(remainingRun.min(day.limit()).minus(quote.inputCost()),
                quote.outputPricePerMillion());
        if (freshDayAllowance < minimumAllowance()) {
            boolean runLimits = remainingRun.compareTo(day.limit()) <= 0;
            return failAttempt(FailureCategory.BUDGET_EXHAUSTED, runLimits ? "budget.cost" : DAILY_LIMIT,
                    runLimits ? "cost budget of this run is used up"
                            : "the daily AI cost limit of " + day.limit() + " cannot pay a single call",
                    null, now);
        }
        return waitForNextBudgetDay(day.renewsAt(), now);
    }

    private int minimumAllowance() {
        return Math.min(MIN_OUTPUT_TOKENS, budget.maxOutputTokens());
    }

    /**
     * Ends the attempt because the daily limit is used up and lets the run wait for the next budget day. The failure
     * stays visible as {@code BUDGET_EXHAUSTED}/{@code budget.daily} while the run waits; the attempt is not counted
     * against the attempt budget.
     */
    private Checkpoint waitForNextBudgetDay(Instant renewsAt, Instant now) {
        recordFailure(FailureCategory.BUDGET_EXHAUSTED, DAILY_LIMIT,
                "daily AI cost limit reached; waiting for the next budget day", now);
        budgetWaits++;
        state = RunState.WAITING_RETRY;
        nextAttemptAt = renewsAt.isAfter(now) ? renewsAt : now.plus(RETRY_BASE);
        return new Halted(state, nextAttemptAt);
    }

    private int affordableTokens(Money available, Money outputPricePerMillion) {
        if (available.amount().signum() <= 0) {
            return 0;
        }
        if (outputPricePerMillion.amount().signum() <= 0) {
            return budget.maxOutputTokens();
        }
        BigDecimal tokens = available.amount().multiply(MILLION)
                .divide(outputPricePerMillion.amount(), 0, RoundingMode.FLOOR);
        return tokens.compareTo(BigDecimal.valueOf(budget.maxOutputTokens())) >= 0 ? budget.maxOutputTokens()
                : tokens.intValue();
    }

    /**
     * Records the usage of a finished model call. Spent money is always recorded, even for a superseded attempt or a
     * run that was cancelled meanwhile, so the cost totals stay truthful.
     */
    public Checkpoint recordModelCall(int forAttempt, String modelId, String outcome, TokenUsage usage, Money callCost,
            Instant now) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(callCost, "cost");
        modelCalls++;
        tokens = tokens.plus(usage);
        cost = cost.plus(callCost);
        if (isCurrent(forAttempt)) {
            attemptModelCalls++;
        }
        step(StepKind.MODEL_CALL, modelId, outcome, usage, callCost, now);
        if (!isCurrent(forAttempt)) {
            return new Superseded();
        }
        if (cost.exceeds(budget.maxCost())) {
            return failAttempt(FailureCategory.BUDGET_EXHAUSTED, "budget.cost",
                    "reported cost " + cost + " exceeds the run budget of " + budget.maxCost(), null, now);
        }
        if (cancelRequested) {
            cancel(now);
            return new Halted(state, null);
        }
        return new Continue();
    }

    /**
     * Admits a tool call. Tools outside the workload grant are refused and counted; repeated attempts to use them
     * stop the run, because the model is then following instructions it must not follow.
     */
    public Checkpoint authorizeToolCall(int forAttempt, String toolName, boolean granted, Instant now) {
        Optional<Checkpoint> stop = interruptions(forAttempt, now);
        if (stop.isPresent()) {
            return stop.get();
        }
        String name = boundedName(toolName);
        if (!granted) {
            deniedToolCalls++;
            step(StepKind.TOOL_DENIED, name, "tool.notGranted", null, null, now);
            if (deniedToolCalls >= MAX_DENIED_TOOL_CALLS) {
                return failAttempt(FailureCategory.TOOL_DENIED, "tool.notGranted",
                        "the model requested tools outside the workload grant " + deniedToolCalls + " times", null,
                        now);
            }
            return new Denied("tool.notGranted");
        }
        if (attemptToolCalls >= budget.maxToolCalls()) {
            return failAttempt(FailureCategory.BUDGET_EXHAUSTED, "budget.toolCalls",
                    "tool call budget of " + budget.maxToolCalls() + " per attempt reached", null, now);
        }
        toolCalls++;
        attemptToolCalls++;
        step(StepKind.TOOL_CALL, name, "tool.allowed", null, null, now);
        return new Continue();
    }

    /** Last checkpoint before a result is applied; a pending cancellation wins over the result. */
    public Checkpoint readyToComplete(int forAttempt, Instant now) {
        if (!isCurrent(forAttempt)) {
            return new Superseded();
        }
        if (cancelRequested) {
            cancel(now);
            return new Halted(state, null);
        }
        return new Continue();
    }

    /**
     * Accepts the validated result of the current attempt. {@code effectAlreadyApplied} records that another run had
     * already applied the same effect, so this run only stores its result.
     */
    public void succeed(int forAttempt, String result, boolean effectAlreadyApplied, Instant now) {
        if (!isCurrent(forAttempt)) {
            throw new IllegalStateException("run " + id + " attempt " + forAttempt + " is not current");
        }
        output = result;
        failure = null;
        finish(RunState.SUCCEEDED, now);
        step(StepKind.COMPLETED, model.model(), effectAlreadyApplied ? "effect.alreadyApplied" : "run.succeeded", null,
                null, now);
    }

    /**
     * Ends the current attempt with a failure. Retryable categories wait for the next attempt with exponential
     * backoff (at least the provider's retry-after); others fail the run.
     */
    public Checkpoint fail(int forAttempt, FailureCategory category, String code, String message,
            Duration retryAfter, Instant now) {
        if (!isCurrent(forAttempt)) {
            return new Superseded();
        }
        return failAttempt(category, code, message, retryAfter, now);
    }

    /**
     * Requests cancellation. Waiting runs stop immediately; a running attempt stops at its next checkpoint and never
     * applies its result. Returns false when the run had already ended.
     */
    public boolean requestCancel(MemberId by, Instant now) {
        if (state.terminal()) {
            return false;
        }
        cancelRequested = true;
        cancelRequestedBy = Objects.requireNonNull(by, "member");
        if (state != RunState.RUNNING) {
            cancel(now);
        }
        return true;
    }

    /** Whether this run counts against the concurrency limits. */
    public boolean occupiesSlot(Instant now) {
        return state == RunState.RUNNING && attemptStartedAt != null
                && attemptStartedAt.plus(budget.maxDuration()).plus(SLOT_GRACE).isAfter(now);
    }

    /** Whether this run is due to start its next attempt and only a free slot is missing. */
    public boolean awaitsSlot(Instant now) {
        return !cancelRequested && (state == RunState.QUEUED
                || state == RunState.WAITING_RETRY && (nextAttemptAt == null || !nextAttemptAt.isAfter(now)));
    }

    /**
     * Reserves a freed slot for this waiting run. The offer ends when the next attempt starts, when the run ends, or
     * when it is not taken up within the validity the caller checks with {@link #holdsSlotOffer}.
     */
    public void offerSlot(Instant now) {
        if (!awaitsSlot(now)) {
            throw new IllegalStateException("run " + id + " does not wait for a slot");
        }
        slotOfferedAt = Objects.requireNonNull(now, "now");
    }

    /** Whether a slot offered to this waiting run within {@code validity} before {@code now} is still reserved. */
    public boolean holdsSlotOffer(Instant now, Duration validity) {
        return slotOfferedAt != null && awaitsSlot(now) && slotOfferedAt.plus(validity).isAfter(now);
    }

    public boolean retryable() {
        return state == RunState.FAILED || state == RunState.CANCELLED;
    }

    private Optional<Checkpoint> interruptions(int forAttempt, Instant now) {
        if (!isCurrent(forAttempt)) {
            return Optional.of(new Superseded());
        }
        if (cancelRequested) {
            cancel(now);
            return Optional.of(new Halted(state, null));
        }
        if (!now.isBefore(attemptStartedAt.plus(budget.maxDuration()))) {
            return Optional.of(failAttempt(FailureCategory.BUDGET_EXHAUSTED, "budget.time",
                    "time budget of " + budget.maxDuration() + " per attempt reached", null, now));
        }
        return Optional.empty();
    }

    private Checkpoint failAttempt(FailureCategory category, String code, String message, Duration retryAfter,
            Instant now) {
        recordFailure(category, code, message, now);
        if (cancelRequested) {
            cancel(now);
            return new Halted(state, null);
        }
        boolean retry = category.retryable() && attemptsUsed() < budget.maxAttempts()
                && cost.compareTo(budget.maxCost()) < 0;
        if (retry) {
            state = RunState.WAITING_RETRY;
            nextAttemptAt = now.plus(backoff(attempt, retryAfter));
            return new Halted(state, nextAttemptAt);
        }
        finish(RunState.FAILED, now);
        return new Halted(state, null);
    }

    static Duration backoff(int attempt, Duration retryAfter) {
        long factor = 1L << Math.max(0, Math.min(attempt - 1, 20));
        Duration delay = RETRY_BASE.multipliedBy(factor);
        if (delay.compareTo(RETRY_MAX) > 0) {
            delay = RETRY_MAX;
        }
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter.compareTo(RETRY_AFTER_MAX) > 0 ? RETRY_AFTER_MAX : retryAfter;
        }
        return delay;
    }

    private void recordFailure(FailureCategory category, String code, String message, Instant now) {
        failure = new RunFailure(category, code, message, now);
        step(StepKind.ATTEMPT_FAILED, category.name(), code, null, null, now);
    }

    private void cancel(Instant now) {
        finish(RunState.CANCELLED, now);
        step(StepKind.CANCELLED, null, "run.cancelled", null, null, now);
    }

    private void finish(RunState terminal, Instant now) {
        state = terminal;
        finishedAt = now;
        nextAttemptAt = null;
        slotOfferedAt = null;
    }

    private boolean isCurrent(int forAttempt) {
        return state == RunState.RUNNING && attempt == forAttempt;
    }

    private Duration remaining(Instant now) {
        return Duration.between(now, attemptStartedAt.plus(budget.maxDuration()));
    }

    private void step(StepKind kind, String name, String outcome, TokenUsage usage, Money stepCost, Instant now) {
        stepCount++;
        pendingSteps.add(new RunStep(stepCount, attempt, kind, name == null ? null : boundedName(name), outcome, usage,
                stepCost, now));
    }

    private static String boundedName(String name) {
        if (name == null) {
            return "";
        }
        String printable = name.replaceAll("\\p{Cntrl}", " ").strip();
        return printable.length() <= MAX_NAME ? printable : printable.substring(0, MAX_NAME);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new IllegalArgumentException("idempotency key must have 1 to 200 characters");
        }
        return key;
    }

    /** Steps appended since the run was loaded or last saved. */
    public List<RunStep> pendingSteps() {
        return List.copyOf(pendingSteps);
    }

    /** Called by the repository after a successful insert or version-checked update. */
    public void persisted() {
        pendingSteps.clear();
        if (fresh) {
            fresh = false;
        } else {
            version++;
        }
    }

    public boolean isNew() {
        return fresh;
    }

    public RunId id() {
        return id;
    }

    /**
     * Whether this run is the same unit of work as a request: same workload, same analyzed sources and versions and
     * same parameters. The requesting member is deliberately not part of it, so both household members asking for
     * the same work share one run.
     */
    public boolean sameWork(Workload otherWorkload, List<SourceRef> otherSources, Map<String, String> otherParameters) {
        return workload == otherWorkload && sources.equals(List.copyOf(otherSources == null ? List.of() : otherSources))
                && parameters.equals(Map.copyOf(otherParameters == null ? Map.of() : otherParameters));
    }

    public Workload workload() {
        return workload;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Optional<RunId> retryOf() {
        return Optional.ofNullable(retryOf);
    }

    public MemberId requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public ModelSelection model() {
        return model;
    }

    public RunBudget budget() {
        return budget;
    }

    public List<SourceRef> sources() {
        return sources;
    }

    public Map<String, String> parameters() {
        return new LinkedHashMap<>(parameters);
    }

    public RunState state() {
        return state;
    }

    /** Number of the current or latest attempt; it identifies the attempt and also counts budget waits. */
    public int attempt() {
        return attempt;
    }

    /** Attempts counted against {@link RunBudget#maxAttempts()}: all but those that only waited for the daily limit. */
    public int attemptsUsed() {
        return attempt - budgetWaits;
    }

    public int budgetWaits() {
        return budgetWaits;
    }

    public Optional<Instant> attemptStartedAt() {
        return Optional.ofNullable(attemptStartedAt);
    }

    public Optional<Instant> nextAttemptAt() {
        return Optional.ofNullable(nextAttemptAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public Optional<Instant> slotOfferedAt() {
        return Optional.ofNullable(slotOfferedAt);
    }

    public Optional<MemberId> cancelRequestedBy() {
        return Optional.ofNullable(cancelRequestedBy);
    }

    public int modelCalls() {
        return modelCalls;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int deniedToolCalls() {
        return deniedToolCalls;
    }

    public int attemptModelCalls() {
        return attemptModelCalls;
    }

    public int attemptToolCalls() {
        return attemptToolCalls;
    }

    public TokenUsage tokens() {
        return tokens;
    }

    public Money cost() {
        return cost;
    }

    public Optional<String> output() {
        return Optional.ofNullable(output);
    }

    public Optional<RunFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public long version() {
        return version;
    }

    public int stepCount() {
        return stepCount;
    }
}
