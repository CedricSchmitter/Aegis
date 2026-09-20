package dev.aegis.documents.domain.analysis;

import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.analysis.AnalysisValues.DocumentDate;
import java.time.LocalDate;
import java.util.Collection;
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
 * <li>A document is filed automatically only into an existing folder and only under
 * {@link #placesAutomatically}; a path that does not exist yet is always a suggestion, because Aegis never creates
 * folders without a member (ADR-0005).</li>
 * </ul>
 */
public final class ClassificationPolicy {
    public static final double APPLY_CONFIDENCE = 0.85;
    public static final double CANDIDATE_CONFIDENCE = 0.6;
    public static final double PLACE_CONFIDENCE = 0.85;
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

    /**
     * Whether an automatic placement may be applied without review (ADR-0005). The folder must already exist, the
     * document must still be in Unsorted and never have been placed or moved by a member, the result must carry no
     * doubt flag, the model's own confidence must be at least {@value #PLACE_CONFIDENCE}, a verbatim quote from the
     * text must support the placement, and the folder's implied labels must be evidence of their own: it implies at
     * least one label and every label it implies is among the document's effective labels. A folder name alone is
     * never evidence (ADR-0002), so folders without implied labels only ever receive a suggestion.
     *
     * @param impliedLabels the labels the target folder and its ancestors pass on
     * @param effectiveLabels the document's effective labels after this run
     */
    public static <T> boolean placesAutomatically(boolean doubtful, Double confidence, boolean evidenced,
            boolean unsorted, boolean placedByMember, Collection<T> impliedLabels, Collection<T> effectiveLabels) {
        return !doubtful && evidenced && unsorted && !placedByMember && confidence != null
                && confidence >= PLACE_CONFIDENCE && !impliedLabels.isEmpty()
                && effectiveLabels.containsAll(impliedLabels);
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
