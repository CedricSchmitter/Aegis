package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;
import java.util.List;
import java.util.UUID;

/** Visible AI configuration and runs; both members see every run of the household. */
public interface AiRunQueries {
    AiOverview overview(Actor actor);

    List<RunView> recent(Actor actor, int limit);

    /** One run with its audit steps. */
    RunView get(Actor actor, UUID runId);

    /** The expected cost of one run of {@code workload} for this member against today's budget. */
    WorkloadEstimate estimate(Actor actor, String workload);
}
