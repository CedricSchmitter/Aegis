package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ImportDocument;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.application.ports.OriginalStore.StoredOriginal;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.TitleOrigin;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Capture use cases. Writes the original to ingress first, validates it, then registers record and job atomically.
 * An interrupted upload therefore never produces a "saved" acknowledgement.
 */
public class DocumentCaptureService implements UploadDocument, ImportDocument {
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private final OriginalStore store;
    private final RegisterReceivedDocument registrar;
    private final DocumentPresenter presenter;
    private final CategoryRepository categories;
    private final HouseholdAccess access;
    private final ArchivalSettings settings;
    private final AnalysisSettings analysis;
    private final Clock clock;

    public DocumentCaptureService(OriginalStore store, RegisterReceivedDocument registrar,
            DocumentPresenter presenter, CategoryRepository categories, HouseholdAccess access,
            ArchivalSettings settings, AnalysisSettings analysis, Clock clock) {
        this.store = store;
        this.registrar = registrar;
        this.presenter = presenter;
        this.categories = categories;
        this.access = access;
        this.settings = settings;
        this.analysis = analysis;
        this.clock = clock;
    }

    @Override
    public UploadResult upload(Actor actor, UploadCommand command) {
        access.requireMember(actor);
        String filename = safeFilename(command.originalFilename());
        if (command.declaredSize() > settings.maxUploadBytes()) {
            throw new InvalidInputException("document.tooLarge", "Upload exceeds " + settings.maxUploadBytes());
        }
        if (command.categoryId() != null) {
            // Rejected before the file is stored: a chosen folder that does not exist is a client error.
            categories.load().require(new CategoryId(command.categoryId()));
        }
        DocumentId id = DocumentId.random();
        StoredOriginal stored = storeValidated(id, command.content());
        Set<Subject> subjects = Subjects.fromViews(command.subjects());
        var registration = registrar.register(new RegisterReceivedDocument.RegistrationCommand(id, titleFrom(filename),
                TitleOrigin.FILENAME, filename, PDF_CONTENT_TYPE, stored.sizeBytes(), stored.checksum(),
                actor.memberId(), clock.instant(), subjects, stored.stagingPath(), command.categoryId(),
                analysis.automatic(), null));
        if (registration.duplicate()) {
            deleteQuietly(stored.stagingPath());
        }
        return new UploadResult(presenter.summarize(registration.document()), registration.duplicate());
    }

    @Override
    public ImportResult importDocument(Actor actor, ImportDocumentCommand command) {
        access.requireMember(actor);
        // Each attempt owns its staging file, even when restoring an existing stable document id.
        // Duplicate cleanup must never delete or overwrite the original referenced by an existing record.
        StoredOriginal stored = storeValidated(DocumentId.random(), command.content());
        Checksum expected = Checksum.of(command.expectedChecksumSha256());
        if (!stored.checksum().equals(expected)) {
            deleteQuietly(stored.stagingPath());
            throw new InvalidInputException("document.checksumMismatch",
                    "Imported content does not match the exported checksum for " + command.id());
        }
        Instant uploadedAt = command.uploadedAt() == null ? clock.instant() : command.uploadedAt();
        // Restored documents are never analyzed automatically; the export carries their earlier results.
        var registration = registrar.register(new RegisterReceivedDocument.RegistrationCommand(command.id(),
                command.title(), titleOrigin(command.titleOrigin()), safeFilename(command.originalFilename()),
                PDF_CONTENT_TYPE, stored.sizeBytes(), stored.checksum(), command.uploadedBy(), uploadedAt,
                Subjects.fromViews(command.subjects()), stored.stagingPath(), null, false, command.trash()));
        if (registration.duplicate()) {
            deleteQuietly(stored.stagingPath());
            if (!registration.document().id().equals(command.id())) {
                throw new InvalidInputException("document.importConflict",
                        "Imported checksum already belongs to another document id");
            }
            return new ImportResult(registration.document().id(), ImportOutcome.ALREADY_PRESENT);
        }
        return new ImportResult(registration.document().id(), ImportOutcome.IMPORTED);
    }

    private StoredOriginal storeValidated(DocumentId id, InputStream content) {
        StoredOriginal stored;
        try {
            stored = store.store(id, content);
        } catch (IOException e) {
            throw new UncheckedIOException("could not store original for " + id, e);
        }
        if (stored.sizeBytes() == 0) {
            deleteQuietly(stored.stagingPath());
            throw new InvalidInputException("document.empty", "Uploaded file is empty");
        }
        if (stored.sizeBytes() > settings.maxUploadBytes()) {
            deleteQuietly(stored.stagingPath());
            throw new InvalidInputException("document.tooLarge", "Upload exceeds " + settings.maxUploadBytes());
        }
        if (!stored.pdfSignature()) {
            deleteQuietly(stored.stagingPath());
            throw new InvalidInputException("document.notPdf", "Only PDF files are accepted");
        }
        return stored;
    }

    private void deleteQuietly(String stagingPath) {
        try {
            store.delete(stagingPath);
        } catch (IOException ignored) {
            // The reconciliation job quarantines leftover files without a record.
        }
    }

    static String safeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new InvalidInputException("document.filename.missing", "Filename is required");
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        name = name.replaceAll("[\\p{Cntrl}]", "").replaceAll("[<>:\"|?*]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new InvalidInputException("document.filename.invalid", "Filename is invalid");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new InvalidInputException("document.notPdf", "Only PDF files are accepted");
        }
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }

    /** Exports before schema 3 carry no title origin; such titles are treated as chosen by a member. */
    static TitleOrigin titleOrigin(String exported) {
        if (exported == null) {
            return TitleOrigin.MANUAL;
        }
        try {
            return TitleOrigin.valueOf(exported);
        } catch (IllegalArgumentException unknown) {
            return TitleOrigin.MANUAL;
        }
    }

    static String titleFrom(String filename) {
        String base = filename.substring(0, filename.length() - ".pdf".length()).replace('_', ' ').trim();
        return base.isEmpty() ? filename : base;
    }
}
