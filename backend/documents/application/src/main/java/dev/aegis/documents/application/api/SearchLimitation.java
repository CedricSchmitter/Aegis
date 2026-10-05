package dev.aegis.documents.application.api;

/** Stable, content-free reason why archive traversal did not finish. */
public enum SearchLimitation {
    REQUEST_LIMIT, TIME_LIMIT, HIT_LIMIT, ARCHIVE_FAILURE, INVALID_PAGINATION, UNSAFE_PAGINATION
}
