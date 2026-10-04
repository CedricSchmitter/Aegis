package dev.aegis.assistant.adapters.rest;

import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.ModelOptionView;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.StepView;
import dev.aegis.assistant.application.api.WorkloadView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representations of AI runs and configuration. Codes are stable English values localized by the UI. */
public final class AiDtos {
    private AiDtos() {
    }

    @Schema(name = "AiSourceDto")
    public record SourceDto(String kind, String id, long version) {
        static SourceDto from(SourceVersion source) {
            return new SourceDto(source.kind(), source.id(), source.version());
        }
    }

    public record StepDto(int seq, int attempt,
            @Schema(allowableValues = {"ATTEMPT_STARTED", "MODEL_CALL", "TOOL_CALL", "TOOL_DENIED", "ATTEMPT_FAILED",
                    "CANCELLED", "COMPLETED"}) String kind,
            @Schema(nullable = true) String name, @Schema(nullable = true) String outcome, long inputTokens,
            long outputTokens, @Schema(nullable = true) BigDecimal cost, Instant at) {
        static StepDto from(StepView step) {
            return new StepDto(step.seq(), step.attempt(), step.kind(), step.name(), step.outcome(),
                    step.inputTokens(), step.outputTokens(), step.cost(), step.at());
        }
    }

    public record RunDto(UUID id,
            @Schema(allowableValues = {"CONNECTION_CHECK", "DOCUMENT_CLASSIFICATION", "DOCUMENT_REVIEW", "CHAT"})
            String workload,
            @Schema(allowableValues = {"QUEUED", "RUNNING", "WAITING_RETRY", "SUCCEEDED", "FAILED", "CANCELLED"})
            String state,
            @Schema(nullable = true) UUID retryOf, UUID requestedBy, Instant requestedAt, String provider,
            String model,
            @Schema(description = "Attempts counted against maxAttempts; waits for the next budget day do not count")
            int attempt, int maxAttempts, boolean cancelRequested, int modelCalls, int toolCalls,
            int deniedToolCalls, long inputTokens, long outputTokens,
            @Schema(description = "Input tokens the provider read from its prompt cache, not part of inputTokens")
            long cacheReadTokens,
            @Schema(description = "Input tokens the provider wrote to its prompt cache, not part of inputTokens")
            long cacheWriteTokens, BigDecimal cost, BigDecimal maxCost,
            String currency, @Schema(nullable = true, description = "Validated result as JSON") String output,
            @Schema(nullable = true) String failureCategory, @Schema(nullable = true) String failureCode,
            @Schema(nullable = true) String failureMessage, @Schema(nullable = true) Instant nextAttemptAt,
            @Schema(nullable = true) Instant finishedAt, List<SourceDto> sources, List<StepDto> steps,
            long version) {
        static RunDto from(RunView run) {
            return new RunDto(run.id(), run.workload(), run.state(), run.retryOf(), run.requestedBy(),
                    run.requestedAt(), run.provider(), run.model(), run.attempt(), run.maxAttempts(),
                    run.cancelRequested(), run.modelCalls(), run.toolCalls(), run.deniedToolCalls(),
                    run.inputTokens(), run.outputTokens(), run.cacheReadTokens(), run.cacheWriteTokens(), run.cost(),
                    run.maxCost(), run.currency(), run.output(),
                    run.failureCategory(), run.failureCode(), run.failureMessage(), run.nextAttemptAt(),
                    run.finishedAt(), run.sources().stream().map(SourceDto::from).toList(),
                    run.steps().stream().map(StepDto::from).toList(), run.version());
        }
    }

    public record WorkloadDto(String workload, @Schema(nullable = true) String provider,
            @Schema(nullable = true) String model,
            @Schema(nullable = true, allowableValues = {"opus", "sonnet", "haiku"},
                    description = "Household model family for new runs; none for the connection check") String family,
            @Schema(description = "Version of the model choice, sent back when changing it") long choiceVersion,
            @Schema(nullable = true, description = "Member who last changed the model choice") UUID choiceChangedBy,
            @Schema(nullable = true) Instant choiceChangedAt, boolean providerConfigured, boolean handlerAvailable,
            boolean available,
            @Schema(description = "Session limit: the cost budget of one new run on the shown provider")
            BigDecimal maxCost, long maxDurationSeconds, int maxModelCalls, int maxToolCalls,
            int maxOutputTokens, int maxAttempts, int maxConcurrent,
            @Schema(description = "Daily limit that applies to this workload on the shown provider: its own limit, "
                    + "otherwise the provider's limit shared with the other workloads without own limits")
            BigDecimal dailyCostLimit,
            @Schema(description = "Spent today under dailyCostLimit") BigDecimal spentToday,
            @Schema(description = "Whether the household set this workload's daily and session limit") boolean ownLimits,
            @Schema(description = "Whether the household can set this workload's limits; not for the connection check")
            boolean limitsSelectable,
            @Schema(description = "Version of the cost limits, sent back when changing them") long limitsVersion,
            @Schema(nullable = true, description = "Member who last changed the cost limits") UUID limitsChangedBy,
            @Schema(nullable = true) Instant limitsChangedAt) {
        static WorkloadDto from(WorkloadView view) {
            return new WorkloadDto(view.workload(), view.provider(), view.model(), view.family(),
                    view.choiceVersion(), view.choiceChangedBy(), view.choiceChangedAt(), view.providerConfigured(),
                    view.handlerAvailable(), view.available(), view.maxCost(), view.maxDurationSeconds(),
                    view.maxModelCalls(), view.maxToolCalls(), view.maxOutputTokens(), view.maxAttempts(),
                    view.maxConcurrent(), view.dailyCostLimit(), view.spentToday(), view.ownLimits(),
                    view.limitsSelectable(), view.limitsVersion(), view.limitsChangedBy(), view.limitsChangedAt());
        }
    }

    public record AiOverviewDto(boolean configured,
            @Schema(description = "Whether the authenticated household member may use a configured provider") boolean memberAllowed,
            @Schema(description = "Provider whose daily limit and spending today are shown: the one this member's "
                    + "runs use now (anthropic or claude-code)", nullable = true) String limitProvider,
            @Schema(description = "Daily limit shared by the workloads without own limits") BigDecimal dailyCostLimit,
            @Schema(description = "Spent today by the workloads without own limits") BigDecimal spentToday, String currency, int maxConcurrentRuns,
            List<WorkloadDto> workloads,
            @Schema(description = "Model families the household can choose per workload") List<ModelOptionDto> models) {
        static AiOverviewDto from(AiOverview overview) {
            return new AiOverviewDto(overview.configured(), overview.memberAllowed(), overview.limitProvider(),
                    overview.dailyCostLimit(), overview.spentToday(), overview.currency(), overview.maxConcurrentRuns(),
                    overview.workloads().stream().map(WorkloadDto::from).toList(),
                    overview.models().stream().map(ModelOptionDto::from).toList());
        }
    }

    @Schema(description = "A model family with the model the Claude API uses for it and its list prices per million "
            + "tokens; the owner's subscription uses the newest model of the family that Claude Code offers")
    public record ModelOptionDto(@Schema(allowableValues = {"opus", "sonnet", "haiku"}) String family, String model,
            @Schema(nullable = true) BigDecimal inputPerMillion, @Schema(nullable = true) BigDecimal outputPerMillion) {
        static ModelOptionDto from(ModelOptionView option) {
            return new ModelOptionDto(option.family(), option.model(), option.inputPerMillion(),
                    option.outputPerMillion());
        }
    }

    @Schema(description = "New model family of a workload with the choice version the member saw")
    public record ChooseModelRequest(@Schema(allowableValues = {"opus", "sonnet", "haiku"}) String family,
            long expectedVersion) {
    }

    @Schema(description = "Daily limit and session limit (cost budget of one run) of a workload in the household "
            + "currency, each a positive amount with at most two decimals and at most 1000, with the limits version "
            + "the member saw")
    public record SetCostLimitsRequest(BigDecimal dailyCostLimit, BigDecimal maxRunCost, long expectedVersion) {
    }
}
