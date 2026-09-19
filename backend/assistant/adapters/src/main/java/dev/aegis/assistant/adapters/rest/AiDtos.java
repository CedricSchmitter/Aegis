package dev.aegis.assistant.adapters.rest;

import dev.aegis.assistant.application.api.AiOverview;
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
            String model, int attempt, int maxAttempts, boolean cancelRequested, int modelCalls, int toolCalls,
            int deniedToolCalls, long inputTokens, long outputTokens, BigDecimal cost, BigDecimal maxCost,
            String currency, @Schema(nullable = true, description = "Validated result as JSON") String output,
            @Schema(nullable = true) String failureCategory, @Schema(nullable = true) String failureCode,
            @Schema(nullable = true) String failureMessage, @Schema(nullable = true) Instant nextAttemptAt,
            @Schema(nullable = true) Instant finishedAt, List<SourceDto> sources, List<StepDto> steps,
            long version) {
        static RunDto from(RunView run) {
            return new RunDto(run.id(), run.workload(), run.state(), run.retryOf(), run.requestedBy(),
                    run.requestedAt(), run.provider(), run.model(), run.attempt(), run.maxAttempts(),
                    run.cancelRequested(), run.modelCalls(), run.toolCalls(), run.deniedToolCalls(),
                    run.inputTokens(), run.outputTokens(), run.cost(), run.maxCost(), run.currency(), run.output(),
                    run.failureCategory(), run.failureCode(), run.failureMessage(), run.nextAttemptAt(),
                    run.finishedAt(), run.sources().stream().map(SourceDto::from).toList(),
                    run.steps().stream().map(StepDto::from).toList(), run.version());
        }
    }

    public record WorkloadDto(String workload, @Schema(nullable = true) String provider,
            @Schema(nullable = true) String model, boolean providerConfigured, boolean handlerAvailable, boolean available, BigDecimal maxCost,
            long maxDurationSeconds, int maxModelCalls, int maxToolCalls, int maxOutputTokens, int maxAttempts,
            int maxConcurrent) {
        static WorkloadDto from(WorkloadView view) {
            return new WorkloadDto(view.workload(), view.provider(), view.model(), view.providerConfigured(),
                    view.handlerAvailable(), view.available(), view.maxCost(), view.maxDurationSeconds(),
                    view.maxModelCalls(), view.maxToolCalls(), view.maxOutputTokens(), view.maxAttempts(),
                    view.maxConcurrent());
        }
    }

    public record AiOverviewDto(boolean configured,
            @Schema(description = "Whether the authenticated household member may use a configured provider") boolean memberAllowed,
            @Schema(description = "Provider whose daily limit and spending today are shown: the one this member's "
                    + "runs use now (anthropic or claude-code)", nullable = true) String limitProvider,
            BigDecimal dailyCostLimit, BigDecimal spentToday, String currency, int maxConcurrentRuns,
            List<WorkloadDto> workloads) {
        static AiOverviewDto from(AiOverview overview) {
            return new AiOverviewDto(overview.configured(), overview.memberAllowed(), overview.limitProvider(),
                    overview.dailyCostLimit(), overview.spentToday(), overview.currency(), overview.maxConcurrentRuns(),
                    overview.workloads().stream().map(WorkloadDto::from).toList());
        }
    }
}
