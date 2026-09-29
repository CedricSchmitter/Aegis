package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;

/** Household-wide model choice per workload; it applies to both members and to scheduled work. */
public interface AiModelCommands {
    /**
     * Chooses the model family of a workload for new runs; queued and running runs keep their model.
     *
     * @param version the choice version the member saw ({@link WorkloadView#choiceVersion()})
     * @throws dev.aegis.shared.StaleVersionException when the other member changed the choice meanwhile
     */
    AiOverview chooseModel(Actor actor, String workload, String family, long version);
}
