package dev.aegis.assistant.domain;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The household's model family for one workload. It applies to both members and to scheduled work; runs resolve it
 * when they are requested, so a change affects new runs only. Version 0 is the configured default that was never
 * changed; every change needs the version the member saw and records who changed it and when.
 */
public final class WorkloadModelChoice {
    private final Workload workload;
    private ModelFamily family;
    private long version;
    private MemberId changedBy;
    private Instant changedAt;

    private WorkloadModelChoice(Workload workload, ModelFamily family, long version, MemberId changedBy,
            Instant changedAt) {
        this.workload = Objects.requireNonNull(workload, "workload");
        this.family = Objects.requireNonNull(family, "family");
        if (workload == Workload.CONNECTION_CHECK) {
            throw new IllegalArgumentException("the connection check checks the models of the other workloads");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    /** The configured default of a workload that nobody has changed yet. */
    public static WorkloadModelChoice defaultOf(Workload workload, ModelFamily family) {
        return new WorkloadModelChoice(workload, family, 0, null, null);
    }

    public static WorkloadModelChoice restore(Workload workload, ModelFamily family, long version, MemberId changedBy,
            Instant changedAt) {
        return new WorkloadModelChoice(workload, family, version, changedBy, changedAt);
    }

    /**
     * Chooses {@code family} for the workload.
     *
     * @return whether the choice changed; choosing the current family again changes nothing
     * @throws StaleVersionException when {@code expectedVersion} is not the current version
     */
    public boolean choose(ModelFamily family, long expectedVersion, MemberId by, Instant at) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(by, "member");
        Objects.requireNonNull(at, "time");
        if (expectedVersion != version) {
            throw new StaleVersionException("aiModelChoice", workload.name(), expectedVersion, version);
        }
        if (family == this.family) {
            return false;
        }
        this.family = family;
        this.version = version + 1;
        this.changedBy = by;
        this.changedAt = at;
        return true;
    }

    public Workload workload() {
        return workload;
    }

    public ModelFamily family() {
        return family;
    }

    public long version() {
        return version;
    }

    public Optional<MemberId> changedBy() {
        return Optional.ofNullable(changedBy);
    }

    public Optional<Instant> changedAt() {
        return Optional.ofNullable(changedAt);
    }
}
