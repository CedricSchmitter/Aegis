package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.FilenameView;
import dev.aegis.documents.application.api.TextRecognitionView;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.shared.DocumentId;
import java.util.Map;
import dev.aegis.documents.application.api.ProcessingInfo;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Builds read models from aggregates, including truthful retry information from the scheduler. */
public class DocumentPresenter {
    private final ProcessingScheduler scheduler;
    private final ClassificationViews classifications;
    private final AnalysisViews analyses;
    private final ArchiveTextCheckRepository textChecks;

    public DocumentPresenter(ProcessingScheduler scheduler, ClassificationViews classifications,
            AnalysisViews analyses, ArchiveTextCheckRepository textChecks) {
        this.scheduler = scheduler;
        this.classifications = classifications;
        this.analyses = analyses;
        this.textChecks = textChecks;
    }

    public DocumentSummary summarize(Document document) {
        return summarize(document, classifications.viewOf(document.id()));
    }

    public DocumentSummary summarize(Document document, ClassificationView classification) {
        return summarize(document, classification, analyses.partsOf(document, classification),
                textChecks.find(document.id()).orElse(null));
    }

    /** Summaries of a page of documents with one batch lookup of analyses, run states and text checks. */
    public List<DocumentSummary> summarizeAll(List<Document> documents,
            Map<DocumentId, ClassificationView> classificationViews) {
        Map<DocumentId, AnalysisViews.Parts> parts = analyses.partsOf(documents, classificationViews);
        Map<DocumentId, ArchiveTextCheck> checks = documents.isEmpty() ? Map.of()
                : textChecks.findAll(documents.stream().map(Document::id).toList());
        return documents.stream().map(document -> summarize(document,
                classificationViews.getOrDefault(document.id(), ClassificationView.UNSORTED),
                parts.get(document.id()), checks.get(document.id()))).toList();
    }

    private DocumentSummary summarize(Document document, ClassificationView classification,
            AnalysisViews.Parts parts, ArchiveTextCheck textCheck) {
        boolean pending = document.state() == ProcessingState.RECEIVED || document.state() == ProcessingState.SUBMITTED;
        Optional<Instant> next = pending ? scheduler.nextAttempt(document.id()) : Optional.empty();
        var failure = document.lastFailure();
        ProcessingInfo processing = new ProcessingInfo(document.state().name(), document.attempts(),
                failure.map(f -> f.code()).orElse(null), failure.map(f -> f.message()).orElse(null),
                failure.map(f -> f.at()).orElse(null), next.isPresent(), next.orElse(null),
                document.paperlessDocumentId().orElse(null), document.archivedAt().orElse(null));
        boolean originalAvailable = document.isStagingPresent() || document.state() == ProcessingState.ARCHIVED;
        List<SubjectView> own = Subjects.toViews(document.subjects());
        List<SubjectView> inherited = classification.folderSubjects().stream().filter(s -> !own.contains(s))
                .toList();
        ClassificationView view = new ClassificationView(classification.categoryId(), classification.path(),
                classification.labels(), inherited, classification.placement(),
                classification.placementSuggestion(), classification.version());
        return new DocumentSummary(document.id(), document.title(), document.titleOrigin().name(),
                document.originalFilename(), document.contentType(), document.sizeBytes(),
                document.checksum().sha256Hex(), document.uploadedBy(), document.uploadedAt(), own, processing,
                originalAvailable, document.version(), view, parts.filename(), parts.analysis(),
                textRecognition(document, textCheck), archivePdfAvailable(document, textCheck),
                document.trash().orElse(null));
    }

    /** Only a check of the current archive document can tell that the archive keeps an archive version of it. */
    static boolean archivePdfAvailable(Document document, ArchiveTextCheck check) {
        return document.state() == ProcessingState.ARCHIVED && document.paperlessDocumentId().isPresent()
                && check != null && check.appliesTo(document.paperlessDocumentId().get())
                && check.keepsArchiveVersion();
    }

    /** The stored check only speaks for the archive document it measured; until then the check is pending. */
    static TextRecognitionView textRecognition(Document document, ArchiveTextCheck check) {
        if (document.state() != ProcessingState.ARCHIVED || document.paperlessDocumentId().isEmpty()) {
            return new TextRecognitionView(TextRecognitionView.NOT_ARCHIVED, null, null, null);
        }
        if (check == null || !check.appliesTo(document.paperlessDocumentId().get())) {
            return new TextRecognitionView(TextRecognitionView.PENDING, null, null, null);
        }
        return check.amount()
                .map(amount -> new TextRecognitionView(amount.name(), check.recognizedCharacters(),
                        check.checkedAt(), null))
                .orElseGet(() -> new TextRecognitionView(TextRecognitionView.UNAVAILABLE, null, check.checkedAt(),
                        check.nextAttemptAt()));
    }

    public DocumentExportRecord exportRecord(Document document, DocumentExportRecord.Classification classification,
            FilenameView filename, DocumentAnalysis analysis) {
        boolean portable = analysis != null && !analysis.isPristine();
        return new DocumentExportRecord(document.id(), document.title(), document.titleOrigin().name(),
                document.originalFilename(), filename.filename(), document.contentType(), document.sizeBytes(),
                document.checksum().sha256Hex(), document.uploadedBy(), document.uploadedAt(),
                Subjects.toViews(document.subjects()), document.state().name(),
                document.paperlessDocumentId().orElse(null), document.archivedAt().orElse(null), classification,
                portable ? AnalysisRecords.toRecord(analysis) : null, document.trash().orElse(null));
    }
}
