package dev.aegis.identity.application.api;

import java.util.List;
import java.util.Set;

/**
 * Marks the configured accounts as the household's assistant (environment data, applied at startup). Idempotent: an
 * account that already is the assistant is left as it is, and a person is never demoted or promoted otherwise.
 */
public interface DesignateAssistants {

    enum Outcome {
        DESIGNATED,
        ALREADY_ASSISTANT,
        UNKNOWN_USERNAME
    }

    record Designation(String username, Outcome outcome) {
    }

    /** Designates every listed username; a newly designated assistant is shown under {@code displayName}. */
    List<Designation> designateAssistants(Set<String> usernames, String displayName);
}
