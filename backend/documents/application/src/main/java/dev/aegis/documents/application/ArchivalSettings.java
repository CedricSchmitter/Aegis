package dev.aegis.documents.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded retry and retention policy for document capture. {@code pollInterval} is the wait between two checks of a
 * submitted consumption task; {@code retryBase} is the first delay after a retryable failure, which doubles with each
 * attempt.
 */
public record ArchivalSettings(int maxAttempts, Duration pollTimeout, Duration stagingRetention, long maxUploadBytes,
        Duration pollInterval, Duration retryBase) {
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_RETRY_BASE = Duration.ofSeconds(30);

    public ArchivalSettings {
        requirePositive(pollInterval, "pollInterval");
        requirePositive(retryBase, "retryBase");
    }

    /** Settings with the default job timing, for callers that only care about attempts and retention. */
    public ArchivalSettings(int maxAttempts, Duration pollTimeout, Duration stagingRetention, long maxUploadBytes) {
        this(maxAttempts, pollTimeout, stagingRetention, maxUploadBytes, DEFAULT_POLL_INTERVAL, DEFAULT_RETRY_BASE);
    }

    public static ArchivalSettings defaults() {
        return new ArchivalSettings(8, Duration.ofMinutes(20), Duration.ofDays(7), 50L * 1024 * 1024);
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + duration);
        }
    }
}
