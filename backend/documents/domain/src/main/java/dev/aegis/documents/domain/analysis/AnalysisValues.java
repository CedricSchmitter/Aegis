package dev.aegis.documents.domain.analysis;

import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.analysis.AnalysisTypes.DateStatus;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Decision;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Flag;
import dev.aegis.documents.domain.catalog.CatalogNames;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable values recorded by the automatic analysis of a document. */
public final class AnalysisValues {
    private AnalysisValues() {
    }

    /** The issue date of the document with the quote that shows it; never a due date or the upload date. */
    public record DocumentDate(LocalDate value, String evidence, DateStatus status) {
        public static final DocumentDate UNKNOWN = new DocumentDate(null, null, DateStatus.UNKNOWN);

        public DocumentDate {
            Objects.requireNonNull(status, "status");
            if (status == DateStatus.FOUND && value == null) {
                throw new IllegalArgumentException("a found date needs a value");
            }
        }

        /** The date to use in names, only when it was found and verified. */
        public LocalDate usable() {
            return status == DateStatus.FOUND ? value : null;
        }
    }

    /**
     * A name that is not in the vocabulary yet (an organization or topic). It never becomes a label on its own; a
     * member accepting it creates or reuses the label. {@code labelId} is set once accepted.
     */
    public record LabelCandidate(Facet facet, String name, String evidence, Double confidence, Decision decision,
            LabelId labelId, MemberId decidedBy, Instant decidedAt) {
        public LabelCandidate {
            Objects.requireNonNull(facet, "facet");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(decision, "decision");
        }

        public String matchKey() {
            return facet.name() + ":" + CatalogNames.matchKey(name);
        }

        LabelCandidate decided(Decision next, LabelId label, MemberId by, Instant at) {
            return new LabelCandidate(facet, name, evidence, confidence, next, label, by, at);
        }
    }

    /** A proposed subject association (the house or a person). Always a suggestion; subjects concern people. */
    public record SubjectSuggestion(Subject subject, Double confidence, String evidence, Decision decision,
            MemberId decidedBy, Instant decidedAt) {
        public SubjectSuggestion {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(decision, "decision");
        }

        SubjectSuggestion decided(Decision next, MemberId by, Instant at) {
            return new SubjectSuggestion(subject, confidence, evidence, next, by, at);
        }
    }

    /** Values a finished run generated, kept as history when a later run replaces them. */
    public record GeneratedValues(int generation, String runRef, String model, Instant completedAt, String title,
            LocalDate documentDate, String shortTopic) {
    }

    /** Everything a finished run contributes, after the policy decided what is applied and what is suggested. */
    public record Outcome(int generation, String runRef, String model, Instant completedAt, String title,
            Decision titleDecision, String titleBefore, DocumentDate documentDate, String shortTopic,
            LabelId correspondent, Set<Flag> flags, List<LabelCandidate> candidates,
            List<SubjectSuggestion> subjects, int appliedLabels, int suggestedLabels) {
        public Outcome {
            Objects.requireNonNull(documentDate, "document date");
            flags = Set.copyOf(flags);
            candidates = List.copyOf(candidates);
            subjects = List.copyOf(subjects);
        }
    }
}
