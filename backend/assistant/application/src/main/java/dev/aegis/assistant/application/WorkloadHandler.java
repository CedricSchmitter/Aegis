package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.Workload;
import java.util.Optional;
import java.util.Set;

/**
 * Workload-specific part of a run: which tools the model may use, what it is asked, how its answer is validated and
 * how a valid result is applied. The execution boundary around it (budgets, retries, cancellation, audit, idempotent
 * application) is the same for every workload.
 *
 * <p>Handlers read inputs and apply effects only through other contexts' public application contracts with
 * {@link RunContext#actor()}. {@link #sourcesCurrent}, {@link #effectKey} and {@link #apply} run inside the completion
 * transaction; everything else runs outside any transaction.
 */
public interface WorkloadHandler {
    Workload workload();

    /** Names of the tools the model may call. The grant is fixed per workload and never derived from content. */
    default Set<String> grantedTools() {
        return Set.of();
    }

    PreparedRun prepare(RunContext run);

    /**
     * Validates the final answer.
     *
     * @throws InvalidModelOutputException when the answer does not satisfy the workload contract
     */
    ValidatedOutput validate(RunContext run, ModelTurn turn);

    /** Whether every analyzed source still has the version recorded in the run. */
    default boolean sourcesCurrent(RunContext run) {
        return true;
    }

    /**
     * Key that identifies the effect across attempts and runs, typically the workload plus the source versions.
     * Empty when the result has no effect.
     */
    default Optional<String> effectKey(RunContext run, ValidatedOutput output) {
        return Optional.empty();
    }

    /** Applies the result through application commands. Called at most once per effect key. */
    default void apply(RunContext run, ValidatedOutput output) {
    }
}
