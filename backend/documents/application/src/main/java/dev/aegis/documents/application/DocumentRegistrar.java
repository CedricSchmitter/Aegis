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
