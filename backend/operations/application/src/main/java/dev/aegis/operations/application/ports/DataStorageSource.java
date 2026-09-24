package dev.aegis.operations.application.ports;

/** Measures the file system that holds the household data (originals, archive, databases). */
public interface DataStorageSource {
    /** @throws RuntimeException when the file system cannot be measured */
    DiskUsage measure();
}
