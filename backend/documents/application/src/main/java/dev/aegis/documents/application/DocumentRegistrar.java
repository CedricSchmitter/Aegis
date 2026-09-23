package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.shared.InvalidInputException;
import java.util.Optional;

/**
 * Records a received document, its classification and its archival job in one unit of work; duplicates return the
 * existing record unchanged. A folder deleted in the meantime never fails the upload: the document stays Unsorted.
 */
public class DocumentRegistrar implements RegisterReceivedDocument {
    private final DocumentRepository documents;
    private final ClassificationRepository classifications;
    private final CategoryRepository categories;
    private final CatalogLock lock;
    private final ProcessingScheduler scheduler;
    private final DocumentAnalysisRepository analyses;

    public DocumentRegistrar(DocumentRepository documents, ClassificationRepository classifications,
            CategoryRepository categories, CatalogLock lock, ProcessingScheduler scheduler,
            DocumentAnalysisRepository analyses) {
        this.documents = documents;
        this.classifications = classifications;
        this.categories = categories;
        this.lock = lock;
        this.scheduler = scheduler;
        this.analyses = analyses;
    }

    @Override
    public RegistrationResult register(RegistrationCommand command) {
        Optional<Document> sameId = documents.findById(command.id());
        if (sameId.isPresent()) {
            if (!sameId.get().checksum().equals(command.checksum())) {
                throw new InvalidInputException("document.importConflict",
                        "Document id already belongs to different content");
            }
            return new RegistrationResult(sameId.get(), true);
        }
        Optional<Document> existing = documents.findByChecksum(command.checksum());
        if (existing.isPresent()) {
            return new RegistrationResult(existing.get(), true);
        }
        Document document = Document.received(command.id(), command.title(), command.titleOrigin(),
                command.originalFilename(), command.contentType(), command.sizeBytes(), command.checksum(),
                command.uploadedBy(), command.uploadedAt(), command.subjects(), command.stagingPath());
        if (command.trash() != null) {
            document.moveToTrash(command.trash());
        }
        documents.save(document);
        DocumentClassification classification = DocumentClassification.unsorted(document.id());
        if (command.categoryId() != null) {
            lock.shared();
            CategoryId category = new CategoryId(command.categoryId());
            if (categories.load().find(category).isPresent()) {
                classification.placeIn(category, command.uploadedBy(), command.uploadedAt());
            }
        }
        classifications.save(classification);
        if (command.analyzeWhenArchived()) {
            analyses.save(DocumentAnalysis.awaitingArchive(document.id(), command.uploadedBy(),
                    command.uploadedAt()));
        }
        scheduler.scheduleArchival(document.id());
        return new RegistrationResult(document, false);
    }
}
