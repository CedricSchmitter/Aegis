package dev.aegis.assistant.application.ports;

/**
 * Exclusive lock held until the surrounding transaction ends. Serializes run admission (concurrency counting) and
 * idempotent run creation across threads and job executions.
 */
public interface AiLock {
    void exclusive();
}
