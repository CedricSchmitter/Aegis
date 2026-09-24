package dev.aegis.operations.application;

/**
 * When a disk counts as low on space (ADR-0015): less than {@code minFreeFraction} of its size or less than
 * {@code minFreeBytes} free, whichever limit is higher. The owner chose 10 % and 5 GB on 2026-09-24.
 */
public record StorageLimits(double minFreeFraction, long minFreeBytes) {
    public StorageLimits {
        if (!(minFreeFraction > 0 && minFreeFraction < 1) || minFreeBytes < 0) {
            throw new IllegalArgumentException("the free-space fraction must be between 0 and 1, the bytes positive");
        }
    }
}
