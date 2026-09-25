package dev.aegis.operations.application.api;

import java.time.Instant;

/**
 * Free space on one disk Aegis depends on (issue #100, ADR-0015): {@code data} holds the originals, the archive and
 * the databases, {@code backup} is the external backup disk. {@code state} is OK, LOW (less than
 * {@code lowBelowBytes} free) or UNKNOWN (the disk could not be measured; the byte values are then null).
 */
public record DiskSpace(String name, String state, Long totalBytes, Long freeBytes, Long lowBelowBytes,
        Instant measuredAt) {
    public static final String DATA = "data";
    public static final String BACKUP = "backup";

    public static final String OK = "OK";
    public static final String LOW = "LOW";
    public static final String UNKNOWN = "UNKNOWN";

    public static DiskSpace unknown(String name, Instant checkedAt) {
        return new DiskSpace(name, UNKNOWN, null, null, null, checkedAt);
    }
}
