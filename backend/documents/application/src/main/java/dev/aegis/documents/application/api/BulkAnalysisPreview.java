package dev.aegis.documents.application.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a bulk classification would cover and cost before a member starts it (issue #115). {@code documents} is the
 * number that would be included, at most {@code maxDocuments}; {@code limited} tells that the scope holds more, which
 * a later run can cover. {@code sample} lists the first of them. {@code refusal} is the code a start would be refused
 * with for this member ({@code ai.provider.memberNotAllowed}, {@code ai.notConfigured}, ...), {@code runningId} the
 * bulk run that is already running, if any.
 */
public record BulkAnalysisPreview(BulkAnalysisView.Scope scope, int documents, boolean limited, int maxDocuments,
        List<BulkAnalysisView.DocumentRef> sample, Cost cost, String refusal, UUID runningId) {
    public static final int SAMPLE = 10;

    public BulkAnalysisPreview {
        sample = List.copyOf(sample);
    }

    /**
     * Expected cost against the household's daily AI budget. {@code perDocument} is the mean of recent classification
     * runs when {@code basis} is {@code AVERAGE} (from {@code samples} runs), otherwise the run budget, the most one
     * run may cost ({@code RUN_BUDGET}). {@code total} is the estimate for all documents, {@code maximum} the bound
     * from the run budget. {@code documentsToday} fit into what is left of today's limit; {@code days} is the number
     * of budget days the run is expected to need, {@code null} when the daily limit cannot pay for a single run.
     * Estimates from list prices, not charges.
     */
    public record Cost(BigDecimal perDocument, String basis, int samples, BigDecimal total, BigDecimal maximum,
            BigDecimal dailyLimit, BigDecimal spentToday, BigDecimal remainingToday, int documentsToday,
            Integer days, String currency, Instant nextBudgetDay) {
    }
}
