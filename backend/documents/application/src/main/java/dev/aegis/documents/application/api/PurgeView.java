package dev.aegis.documents.application.api;

import dev.aegis.shared.MemberId;
import java.time.Instant;

/**
 * A requested permanent deletion of a trashed document (ADR-0021): {@code PENDING} while the job works on it,
 * {@code FAILED} once it gave up; {@code failureCode} and {@code failedAt} are set only then.
 */
public record PurgeView(String state, MemberId requestedBy, Instant requestedAt, String failureCode,
        Instant failedAt) {
    public static final String PENDING = "PENDING";
    public static final String FAILED = "FAILED";
}
