package dev.aegis.operations.application.api;

import dev.aegis.operations.application.api.JobHealthWatch.Report;

/**
 * Tells the members when the external backup stops working or a disk runs low on space (issue #100, ADR-0015): one
 * in-app notice per member and incident. It runs in the recurring job health watch, never inside the backup itself.
 */
public interface StorageHealthWatch {
    /**
     * Evaluates the backup and every measured disk once. A subject whose check fails ({@code backup},
     * {@code disk:data}, {@code disk:backup}) is named in {@link Report#failed()} and does not stop the others.
     */
    Report check();
}
