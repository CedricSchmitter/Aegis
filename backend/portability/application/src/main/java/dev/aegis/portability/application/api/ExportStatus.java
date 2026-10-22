package dev.aegis.portability.application.api;

import java.time.Instant;

/**
 * Health of the daily export for the status page. {@code latest} is the newest published generation, if any.
 * {@code lastCheckedAt} is when a scheduled run last finished, with or without a new generation;
 * {@code lastFailedAt} when one last failed. Both are null before the first scheduled run.
 */
public record ExportStatus(ExportSummary latest, Instant lastCheckedAt, Instant lastFailedAt) {
}
