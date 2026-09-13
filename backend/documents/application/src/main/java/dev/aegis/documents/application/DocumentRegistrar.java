package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import java.util.Optional;

/** Records a received document and its archival job in one unit of work; duplicates return the existing record. */
public class DocumentRegistrar implements RegisterReceivedDocument {
    private final DocumentRepository documents;
    private final ProcessingScheduler scheduler;

    public DocumentRegistrar(DocumentRepository documents, ProcessingScheduler scheduler) {
        this.documents = documents;
        this.scheduler = scheduler;
    }

    @Override
    public RegistrationResult register(RegistrationCommand command) {
        Optional<Document> sameId = documents.findById(command.id());
        if (sameId.isPresent()) {
            if (!sameId.get().checksum().equals(command.checksum())) {
                throw new dev.aegis.shared.InvalidInputException("document.importConflict",
                        "Document id already belongs to different content");
            }
            return new RegistrationResult(sameId.get(), true);
        }
        Optional<Document> existing = documents.findByChecksum(command.checksum());
        if (existing.isPresent()) {
            return new RegistrationResult(existing.get(), true);
        }
        Document document = Document.received(command.id(), command.title(), command.originalFilename(),
                command.contentType(), command.sizeBytes(), command.checksum(), command.uploadedBy(),
                command.uploadedAt(), command.subjects(), command.stagingPath());
        documents.save(document);
        scheduler.scheduleArchival(document.id());
        return new RegistrationResult(document, false);
    }
}
