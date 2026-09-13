package dev.aegis.documents.application;

import java.time.Duration;

/** Bounded retry and retention policy for document capture. */
public record ArchivalSettings(int maxAttempts, Duration pollTimeout, Duration stagingRetention, long maxUploadBytes) {
    public static ArchivalSettings defaults() {
        return new ArchivalSettings(8, Duration.ofMinutes(20), Duration.ofDays(7), 50L * 1024 * 1024);
    }
}
