package dev.aegis.documents.application;

import java.time.Duration;

/**
 * Bounds of bulk analyses: the most documents one run includes, the runs it keeps in flight, how often
 * its job follows them and the longest pause while it waits for the next budget day.
 */
public record BulkAnalysisSettings(int maxDocuments, int batchSize, Duration pollInterval, Duration maxWait) {
    public BulkAnalysisSettings {
        if (maxDocuments < 1 || batchSize < 1 || pollInterval.isNegative() || pollInterval.isZero()
                || maxWait.compareTo(pollInterval) < 0) {
            throw new IllegalArgumentException("invalid bulk analysis settings");
        }
    }
}
