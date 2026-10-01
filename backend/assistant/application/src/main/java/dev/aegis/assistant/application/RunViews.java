package dev.aegis.assistant.application;

import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.StepView;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.RunFailure;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunStep;
import java.util.List;

/** Maps runs to their public view. */
final class RunViews {
    private RunViews() {
    }

    static RunView view(AiRun run, List<RunStep> steps) {
        RunFailure failure = run.failure().orElse(null);
        return new RunView(run.id().value(), run.workload().name(), run.state().name(),
                run.retryOf().map(RunId::value).orElse(null), run.requestedBy().value(), run.requestedAt(),
                run.model().provider(), run.model().model(), run.attemptsUsed(), run.budget().maxAttempts(),
                run.cancelRequested(), run.modelCalls(), run.toolCalls(), run.deniedToolCalls(),
                run.tokens().inputTokens(), run.tokens().outputTokens(), run.cost().amount(),
                run.budget().maxCost().amount(), run.cost().currency().getCurrencyCode(), run.output().orElse(null),
                failure == null ? null : failure.category().name(), failure == null ? null : failure.code(),
                failure == null ? null : failure.message(), run.nextAttemptAt().orElse(null),
                run.finishedAt().orElse(null),
                run.sources().stream().map(s -> new SourceVersion(s.kind(), s.id(), s.version())).toList(),
                steps.stream().map(RunViews::step).toList(), run.version());
    }

    private static StepView step(RunStep step) {
        return new StepView(step.seq(), step.attempt(), step.kind().name(), step.name(), step.outcome(),
                step.tokens().inputTokens(), step.tokens().outputTokens(),
                step.cost() == null ? null : step.cost().amount(), step.at());
    }
}
