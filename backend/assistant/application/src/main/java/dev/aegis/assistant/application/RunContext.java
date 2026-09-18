package dev.aegis.assistant.application;

import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import java.util.List;
import java.util.Map;

/**
 * What a workload handler and the tools know about the attempt being executed. {@code actor} is the member who
 * requested the run; every read and every effect uses it, so the run never has more rights than that member.
 */
public record RunContext(RunId id, Workload workload, int attempt, Actor actor, ModelSelection model,
        List<SourceRef> sources, Map<String, String> parameters, String effort) {
    public RunContext {
        sources = List.copyOf(sources);
        parameters = Map.copyOf(parameters);
    }
}
