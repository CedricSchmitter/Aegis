package dev.aegis.assistant.domain;

import java.util.Map;
import java.util.Optional;

/** Persistence of the household's model choices; a workload without a stored choice uses its configured default. */
public interface WorkloadModelChoiceRepository {
    Optional<WorkloadModelChoice> find(Workload workload);

    Map<Workload, WorkloadModelChoice> findAll();

    /**
     * Stores a changed choice whose previous version is {@code choice.version() - 1}.
     *
     * @throws dev.aegis.shared.StaleVersionException when another member changed the choice meanwhile
     */
    void save(WorkloadModelChoice choice);
}
