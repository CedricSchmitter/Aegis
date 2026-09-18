package dev.aegis.assistant.application.api;

import java.time.Instant;

/**
 * Operational summary for the status page. {@code lastFinishedState} and {@code lastFailureCategory} describe the
 * most recently finished run; both are null when no run has finished yet.
 */
public record AiHealth(boolean configured, Instant lastSuccessAt, String lastFinishedState,
        String lastFailureCategory, Instant lastFinishedAt) {
}
