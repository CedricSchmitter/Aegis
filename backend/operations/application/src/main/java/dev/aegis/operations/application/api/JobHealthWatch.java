package dev.aegis.operations.application.api;

import dev.aegis.shared.Actor;
import java.util.List;

/**
 * Tells the members when a recurring background job stops running (ADR-0014): one in-app notice per member
 * and incident while a job is stale or not scheduled. It runs as its own recurring job, never inside a watched one.
 */
public interface JobHealthWatch {
    /** The household process the watch runs as; it only reads the member list to address its notices. */
    Actor WATCH = Actor.householdProcess("job-health-watch");

    /**
     * Evaluates every watched job once. A job whose check fails is named in {@link Report#failed()} and does not stop
     * the others.
     */
    Report check();

    /** Incidents opened and resolved by this check, and the jobs that could not be checked. */
    record Report(int opened, int resolved, List<String> failed) {
        public Report {
            failed = List.copyOf(failed);
        }
    }
}
