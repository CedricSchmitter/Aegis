package dev.aegis.documents.domain;

import java.time.Instant;
import java.util.Objects;

/** Last recorded archival failure. Codes are stable English identifiers; messages are diagnostic only. */
public record ProcessingFailure(String code, String message, Instant at) {
    public ProcessingFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");
        message = message == null ? "" : message.length() > 500 ? message.substring(0, 500) : message;
    }
}
