package dev.aegis.assistant.application.api;

import dev.aegis.shared.Actor;
import java.math.BigDecimal;

/** Household-wide cost limits per workload; they apply to both members and to scheduled work. */
public interface AiLimitCommands {
    /**
     * Sets the daily limit and the session limit (cost budget of one run) of a workload in the household currency.
     * New runs use the session limit; queued and running runs keep theirs. The daily limit applies from the next
     * model call of the workload; runs already waiting for the next budget day keep waiting until then.
     *
     * @param version the limits version the member saw ({@link WorkloadView#limitsVersion()})
     * @throws dev.aegis.shared.StaleVersionException when the other member changed the limits meanwhile
     * @throws dev.aegis.shared.InvalidInputException when an amount is not a positive amount in whole cents of at
     *         most 1000, or the session limit is above the daily limit
     */
    AiOverview setCostLimits(Actor actor, String workload, BigDecimal dailyCostLimit, BigDecimal maxRunCost,
            long version);
}
