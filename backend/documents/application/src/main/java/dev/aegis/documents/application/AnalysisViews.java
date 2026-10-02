package dev.aegis.documents.application;

import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.FilenameView;
import dev.aegis.documents.application.api.LabelChip;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.catalog.Facet;
import dev.aegis.documents.domain.catalog.LabelId;
import dev.aegis.documents.domain.naming.BuiltinLabelNames;
import dev.aegis.documents.domain.naming.FriendlyFilename;
import dev.aegis.shared.DocumentId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model of the automatic analysis and the friendly filename. The analysis state combines the stored analysis
 * with the state of its current AI run; the filename is rendered deterministically from the document's effective
 * labels and the evidenced analysis values, so equal inputs always give the same name.
 */
public class AnalysisViews {
    /** Analysis and filename of one document. */
    public record Parts(AnalysisView analysis, FilenameView filename) {
    }

    private final DocumentAnalysisRepository analyses;
    private final AnalysisRuns runs;
    private final AnalysisSettings settings;

    public AnalysisViews(DocumentAnalysisRepository analyses, AnalysisRuns runs, AnalysisSettings settings) {
        this.analyses = analyses;
        this.runs = runs;
        this.settings = settings;
    }

    public Parts partsOf(Document document, ClassificationView classification) {
        return partsOf(List.of(document), Map.of(document.id(), classification)).get(document.id());
    }

    public Map<DocumentId, Parts> partsOf(Collection<Document> documents,
            Map<DocumentId, ClassificationView> classifications) {
        Map<DocumentId, Parts> result = new LinkedHashMap<>();
        if (documents.isEmpty()) {
            return result;
        }
        Map<DocumentId, DocumentAnalysis> stored = analyses.findAll(documents.stream().map(Document::id).toList());
        // The run of the current generation: its progress while requested, its provenance once completed.
        List<String> keys = stored.values().stream()
                .filter(a -> a.status() == Status.REQUESTED || a.status() == Status.COMPLETED)
                .map(a -> AnalysisRuns.key(a.documentId(), a.generation())).toList();
        Map<String, AnalysisRuns.RunStatus> statuses = keys.isEmpty() ? Map.of() : runs.latest(keys);
        for (Document document : documents) {
            DocumentAnalysis analysis = stored.get(document.id());
            ClassificationView classification = classifications.getOrDefault(document.id(),
                    ClassificationView.UNSORTED);
            AnalysisRuns.RunStatus run = analysis == null ? null
                    : statuses.get(AnalysisRuns.key(analysis.documentId(), analysis.generation()));
            result.put(document.id(), new Parts(view(analysis, run, classification),
                    filename(document, classification, analysis, settings.filenameLanguage())));
        }
        return result;
    }

    static AnalysisView view(DocumentAnalysis analysis, AnalysisRuns.RunStatus run,
            ClassificationView classification) {
        if (analysis == null) {
            return AnalysisView.NONE;
        }
        DocumentAnalysis.Snapshot s = analysis.snapshot();
        String state = state(analysis, run, classification);
        String failureCategory = null;
        String failureCode = null;
        if (AnalysisView.FAILED.equals(state)) {
            failureCategory = run == null ? "INTERNAL" : run.failureCategory();
            failureCode = run == null ? "ai.run.missing"
                    : Optional.ofNullable(run.failureCode()).orElse("ai.run." + run.state().toLowerCase());
        } else if (AnalysisView.UNAVAILABLE.equals(state)) {
            failureCode = s.unavailableCode();
        }
        return new AnalysisView(state, s.generation(), s.trigger() == null ? null : s.trigger().name(),
                s.requestedBy(), s.requestedAt(), run == null ? null : run.runId(), run == null ? null : run.state(),
                failureCategory, failureCode, run == null || !run.active() ? null : run.nextAttemptAt(),
                s.completedAt(), s.model(), s.title(), s.titleDecision() == null ? null : s.titleDecision().name(),
                s.documentDate().value(), s.documentDate().evidence(), s.documentDate().status().name(),
                s.shortTopic(), s.correspondent() == null ? null : s.correspondent().value(),
                s.flags().stream().map(Enum::name).sorted().toList(),
                s.candidates().stream().map(c -> new AnalysisView.Candidate(c.matchKey(), c.facet().name(), c.name(),
                        c.evidence(), c.confidence(), c.decision().name(),
                        c.labelId() == null ? null : c.labelId().value())).toList(),
                s.subjects().stream().map(x -> new AnalysisView.SubjectSuggestion(
                        Subjects.toViews(List.of(x.subject())).getFirst(), x.evidence(), x.confidence(),
                        x.decision().name())).toList(),
                s.history().stream().map(h -> new AnalysisView.Generated(h.generation(), h.runRef(), h.model(),
                        h.completedAt(), h.title(), h.documentDate(), h.shortTopic())).toList(),
                analysis.version());
    }

    private static String state(DocumentAnalysis analysis, AnalysisRuns.RunStatus run,
            ClassificationView classification) {
        return switch (analysis.status()) {
            case NOT_REQUESTED -> AnalysisView.NOT_REQUESTED;
            case AWAITING_ARCHIVE -> AnalysisView.WAITING_FOR_ARCHIVE;
            // A requester who may not use AI is an expected configuration state, whether the request was
            // refused at once or the queued run found the access withdrawn.
            case UNAVAILABLE -> AnalysisRuns.MEMBER_NOT_ALLOWED.equals(analysis.snapshot().unavailableCode())
                    ? AnalysisView.NOT_CLASSIFIED : AnalysisView.UNAVAILABLE;
            case REQUESTED -> {
                if (run == null) {
                    yield AnalysisView.FAILED;
                }
                if (run.active()) {
                    // Stopped only by the household's daily limit: the run resumes on the next budget day, which is
                    // no failure.
                    yield "WAITING_RETRY".equals(run.state()) && AnalysisRuns.DAILY_LIMIT.equals(run.failureCode())
                            ? AnalysisView.WAITING_FOR_BUDGET : AnalysisView.PENDING;
                }
                if ("SUCCEEDED".equals(run.state())) {
                    yield AnalysisView.CLASSIFIED;
                }
                yield AnalysisRuns.MEMBER_NOT_ALLOWED.equals(run.failureCode()) ? AnalysisView.NOT_CLASSIFIED
                        : AnalysisView.FAILED;
            }
            // A doubtful result that left the document without any effective label also needs a member's look.
            case COMPLETED -> analysis.hasPendingSuggestions() || pendingLabels(classification)
                    || classification.placementSuggestion() != null
                    || !analysis.flags().isEmpty() && classification.labels().stream().noneMatch(
                            AnalysisViews::effective)
                    ? AnalysisView.NEEDS_REVIEW : AnalysisView.CLASSIFIED;
        };
    }

    private static boolean pendingLabels(ClassificationView classification) {
        return classification.labels().stream().anyMatch(chip -> LabelChip.SOURCE_OWN.equals(chip.source())
                && "AUTOMATIC".equals(chip.origin()) && "SUGGESTED".equals(chip.reviewState()));
    }

    /**
     * The friendly filename: the member's override if set, otherwise the evidenced issue date, the effective
     * organization, the effective document type and the short topic, then the stable id. Date and short topic are
     * used only from a result without doubt flags.
     */
    public static FilenameView filename(Document document, ClassificationView classification,
            DocumentAnalysis analysis, BuiltinLabelNames.Language language) {
        Optional<String> override = analysis == null ? Optional.empty() : analysis.filenameOverride();
        if (override.isPresent()) {
            return new FilenameView(FriendlyFilename.renderOverride(document.id(), override.get(),
                    document.contentType()), FilenameView.MANUAL, override.get());
        }
        List<LabelChip> effective = classification.labels().stream().filter(AnalysisViews::effective).toList();
        // The classification view already hides a folder type behind an own effective type.
        String type = effective.stream().filter(chip -> Facet.DOCUMENT_TYPE.name().equals(chip.facet()))
                .findFirst().map(chip -> name(chip, language)).orElse(null);
        List<LabelChip> organizations = effective.stream()
                .filter(chip -> Facet.ORGANIZATION.name().equals(chip.facet())).toList();
        UUID correspondent = analysis == null ? null
                : analysis.correspondent().map(LabelId::value).orElse(null);
        String organization = organizations.stream().filter(chip -> chip.labelId().equals(correspondent))
                .findFirst().or(() -> organizations.size() == 1 ? Optional.of(organizations.getFirst())
                        : Optional.empty())
                .map(chip -> name(chip, language)).orElse(null);
        // Date and short topic come only from results without doubt flags; labels count once they are effective.
        boolean trusted = analysis != null && analysis.flags().isEmpty();
        var components = new FriendlyFilename.Components(trusted ? analysis.documentDate().usable() : null,
                organization, type, trusted ? analysis.shortTopic().orElse(null) : null);
        boolean generated = components.documentDate() != null || organization != null || type != null
                || components.topic() != null;
        return new FilenameView(FriendlyFilename.render(document.id(), components, document.title(),
                document.contentType()), generated ? FilenameView.GENERATED : FilenameView.FALLBACK, null);
    }

    private static boolean effective(LabelChip chip) {
        return LabelChip.SOURCE_FOLDER.equals(chip.source()) || "APPLIED".equals(chip.reviewState())
                || "CONFIRMED".equals(chip.reviewState());
    }

    private static String name(LabelChip chip, BuiltinLabelNames.Language language) {
        if (chip.name() != null) {
            return chip.name();
        }
        return BuiltinLabelNames.name(Objects.requireNonNullElse(chip.builtinKey(), ""), language);
    }
}
