package dev.aegis.assistant.domain;

import java.util.Map;
import java.util.Optional;

/** Persistence of the household's cost limits; a workload without stored limits uses the configured defaults. */
public interface WorkloadCostLimitsRepository {
    Optional<WorkloadCostLimits> find(Workload workload);

    Map<Workload, WorkloadCostLimits> findAll();

    /**
     * Stores changed limits whose previous version is {@code limits.version() - 1}.
     *
     * @throws dev.aegis.shared.StaleVersionException when another member changed the limits meanwhile
     */
    void save(WorkloadCostLimits limits);
}
