package dev.aegis.documents.application.api;

import java.time.Instant;

/**
 * Observed archive (Paperless) status. {@code reachable} reflects a fresh check, not a cached assumption; the last
 * successful archival is null before the first one.
 */
public record ArchiveHealth(boolean reachable, Instant checkedAt, Instant lastSuccessfulArchivalAt) {
}
