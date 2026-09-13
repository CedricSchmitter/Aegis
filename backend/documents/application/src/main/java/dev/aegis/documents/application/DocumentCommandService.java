package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.util.List;

/** Member commands on documents; wrapped in a transaction by the composition root. */
public class DocumentCommandService implements DocumentCommands {
    private final DocumentRepository documents;
    private final ProcessingScheduler scheduler;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;

    public DocumentCommandService(DocumentRepository documents, ProcessingScheduler scheduler,
            DocumentPresenter presenter, HouseholdAccess access) {
        this.documents = documents;
        this.scheduler = scheduler;
        this.presenter = presenter;
        this.access = access;
    }

    @Override
    public DocumentSummary retryArchival(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = load(id);
        document.requestRetry();
        documents.save(document);
        scheduler.scheduleArchival(id);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary changeSubjects(Actor actor, DocumentId id, List<SubjectView> subjects,
            long expectedVersion) {
        access.requireMember(actor);
        Document document = loadVersion(id, expectedVersion);
        document.changeSubjects(Subjects.fromViews(subjects));
        documents.save(document);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary rename(Actor actor, DocumentId id, String title, long expectedVersion) {
        access.requireMember(actor);
        Document document = loadVersion(id, expectedVersion);
        document.rename(title);
        documents.save(document);
        return presenter.summarize(load(id));
    }

    private Document loadVersion(DocumentId id, long expectedVersion) {
        Document document = load(id);
        if (document.version() != expectedVersion) {
            throw new StaleVersionException("document", id.toString(), expectedVersion, document.version());
        }
        return document;
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
