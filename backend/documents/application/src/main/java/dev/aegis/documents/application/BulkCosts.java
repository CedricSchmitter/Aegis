package dev.aegis.documents.application;

import dev.aegis.documents.application.api.BulkAnalysisPreview;
import dev.aegis.documents.application.ports.AnalysisRuns;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cost estimate of a bulk analysis against the household's daily AI budget (issue #115). The per-document cost is the
 * mean of recent classification runs once there are enough of them, otherwise the run budget, which no run may
 * exceed; both are estimates from list prices.
 */
final class BulkCosts {
    /** Succeeded runs needed before their mean replaces the run budget as the per-document estimate. */
    static final int MIN_SAMPLES = 3;
    private static final int SCALE = 6;

    private BulkCosts() {
    }

    static BulkAnalysisPreview.Cost estimate(AnalysisRuns.CostEstimate estimate, int documents) {
        BigDecimal runBudget = orZero(estimate.runBudget());
        boolean average = estimate.averageCost() != null && estimate.samples() >= MIN_SAMPLES;
        BigDecimal perDocument = scaled(average ? estimate.averageCost() : runBudget);
        BigDecimal count = BigDecimal.valueOf(documents);
        BigDecimal limit = orZero(estimate.dailyLimit());
        BigDecimal remaining = scaled(limit.subtract(orZero(estimate.spentToday())).max(BigDecimal.ZERO));
        // What is left of today's limit pays for this many documents, whether or not the run needs them all.
        long affordable;
        long perDay;
        if (perDocument.signum() <= 0) {
            affordable = Integer.MAX_VALUE;
            perDay = Math.max(documents, 1);
        } else {
            affordable = fits(remaining, perDocument);
            perDay = fits(limit, perDocument);
        }
        int today = (int) Math.min(documents, affordable);
        Integer days;
        if (documents <= today) {
            days = documents == 0 ? 0 : 1;
        } else if (perDay == 0) {
            days = null;
        } else {
            long rest = documents - today;
            days = (int) Math.min(Integer.MAX_VALUE, 1 + (rest + perDay - 1) / perDay);
        }
        return new BulkAnalysisPreview.Cost(perDocument, average ? "AVERAGE" : "RUN_BUDGET",
                average ? estimate.samples() : 0, scaled(perDocument.multiply(count)),
                scaled(runBudget.multiply(count)), scaled(limit), scaled(orZero(estimate.spentToday())), remaining,
                (int) Math.min(Integer.MAX_VALUE, affordable), days, estimate.currency(), estimate.nextBudgetDay());
    }

    /** How many runs of {@code each} fit into {@code amount}. */
    private static long fits(BigDecimal amount, BigDecimal each) {
        return amount.divide(each, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(Integer.MAX_VALUE)).longValue();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
