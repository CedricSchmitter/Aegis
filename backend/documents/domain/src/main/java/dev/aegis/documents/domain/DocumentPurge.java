package dev.aegis.documents.domain;

import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Objects;

/**
 * A member's request to delete a trashed document permanently (issue #85, ADR-0021). The archive original, its
 * Paperless trash entry, the staging copy and the Aegis record are removed by a background job; until that job
 * finishes the document stays in the trash and can no longer be restored. {@code failureCode} and {@code failedAt}
 * are set once the job gave up; a member can then request the deletion again.
 */
public record DocumentPurge(MemberId requestedBy, Instant requestedAt, int attempts, String failureCode,
        Instant failedAt) {

    public DocumentPurge {
        Objects.requireNonNull(requestedBy, "requested by");
        Objects.requireNonNull(requestedAt, "requested at");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        if ((failureCode == null) != (failedAt == null)) {
            throw new IllegalArgumentException("a failed purge names its failure and time");
        }
    }

    static DocumentPurge requested(MemberId by, Instant now) {
        return new DocumentPurge(by, now, 0, null, null);
    }

    public boolean failed() {
        return failedAt != null;
    }

    DocumentPurge attempted() {
        return new DocumentPurge(requestedBy, requestedAt, attempts + 1, null, null);
    }

    DocumentPurge failedWith(String code, Instant at) {
        return new DocumentPurge(requestedBy, requestedAt, attempts + 1, Objects.requireNonNull(code), at);
    }
}
