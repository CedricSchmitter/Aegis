package dev.aegis.operations.application.ports;

import java.time.Instant;

/** Total and free bytes of one file system at the time of measurement. */
public record DiskUsage(long totalBytes, long freeBytes, Instant measuredAt) {
    public DiskUsage {
        if (totalBytes < 0 || freeBytes < 0 || measuredAt == null) {
            throw new IllegalArgumentException("disk usage needs non-negative sizes and a measurement time");
        }
    }
}
