package dev.aegis.documents.application.api;

import java.time.Instant;

/** Observed archive (Paperless) status. {@code reachable} reflects a fresh check, not a cached assumption. */
public record ArchiveHealth(boolean reachable, String serverVersion, String detail, Instant checkedAt,
        Instant lastSuccessfulArchivalAt) {
}
