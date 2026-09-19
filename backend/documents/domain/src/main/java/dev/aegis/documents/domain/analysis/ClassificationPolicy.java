package dev.aegis.documents.domain.analysis;

import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import java.time.LocalDate;
import java.util.Set;

/**
 * Conservative rule for what an automatic classification may apply without review (ADR-0004). The model's
 * confidence is its own estimate, not a calibrated probability, so it is only one of several conditions:
 *
 * <ul>
 * <li>Nothing is applied when the classifier reports poor OCR, no text, conflicting information, embedded
 * instructions or several documents in one file.</li>
 * <li>A label is applied only if it resolves to an existing active vocabulary entry, the reported confidence is at
 * least {@value #APPLY_CONFIDENCE} and a verbatim quote from the text supports it; everything else is a
 * suggestion. At most {@value #MAX_APPLIED_TOPICS} topics are applied.</li>
 * <li>Names that are not in the vocabulary are never created automatically; with a verified quote and a
 * confidence of at least {@value #CANDIDATE_CONFIDENCE} they are offered as new-name suggestions.</li>
 * <li>A generated title replaces only a filename-derived title; otherwise it is a suggestion.</li>
 * <li>Subject associations are always suggestions.</li>
 * <li>The issue date counts only when its quote is in the text and spells out that date; dates in the future (beyond
 * one year) or before 1900 are rejected.</li>
 * </ul>
 */
public final class ClassificationPolicy {
    public static final double APPLY_CONFIDENCE = 0.85;
    public static final double CANDIDATE_CONFIDENCE = 0.6;
    public static final int MAX_APPLIED_TOPICS = 3;
    public static final int MAX_TOPICS = 5;

    private ClassificationPolicy() {
    }

    public static boolean doubtful(Set<Flag> flags) {
        return !flags.isEmpty();
    }

    /** Whether a label that resolved to an active vocabulary entry may be applied without review. */
    public static boolean applies(boolean doubtful, Double confidence, boolean evidenced) {
        return !doubtful && evidenced && confidence != null && confidence >= APPLY_CONFIDENCE;
    }

    /** Whether an unknown name is worth offering as a new label. */
    public static boolean offersCandidate(Double confidence, boolean evidenced) {
        return evidenced && confidence != null && confidence >= CANDIDATE_CONFIDENCE;
    }

    /** Evaluates a reported issue date against the text. */
    public static DocumentDate documentDate(LocalDate reported, String evidence, boolean conflicting, String text,
            LocalDate today) {
        if (reported == null) {
            return conflicting ? new DocumentDate(null, null, DateStatus.CONFLICTING) : DocumentDate.UNKNOWN;
        }
        String quote = evidence == null ? null : evidence.strip();
        if (conflicting) {
            return new DocumentDate(reported, quote, DateStatus.CONFLICTING);
        }
        boolean plausible = !reported.isBefore(LocalDate.of(1900, 1, 1)) && !reported.isAfter(today.plusYears(1));
        if (plausible && Evidence.datesTo(reported, quote, text)) {
            return new DocumentDate(reported, quote, DateStatus.FOUND);
        }
        return new DocumentDate(reported, quote, DateStatus.UNVERIFIED);
    }
}
