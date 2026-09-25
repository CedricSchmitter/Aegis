package dev.aegis.documents.application;

import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import dev.aegis.shared.Trash;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/** Member commands on documents; wrapped in a transaction by the composition root. */
public class DocumentCommandService implements DocumentCommands {
    private final DocumentRepository documents;
    private final ProcessingScheduler scheduler;
    private final DocumentPresenter presenter;
    private final AnalysisTrigger analysis;
    private final HouseholdPersons persons;
    private final HouseholdAccess access;
    private final Trash.RecoveryWindow recoveryWindow;
    private final Clock clock;

    public DocumentCommandService(DocumentRepository documents, ProcessingScheduler scheduler,
            DocumentPresenter presenter, AnalysisTrigger analysis, HouseholdPersons persons, HouseholdAccess access,
            Trash.RecoveryWindow recoveryWindow, Clock clock) {
        this.documents = documents;
        this.scheduler = scheduler;
        this.presenter = presenter;
        this.analysis = analysis;
        this.persons = persons;
        this.access = access;
        this.recoveryWindow = recoveryWindow;
        this.clock = clock;
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
        Set<Subject> wanted = Subjects.fromViews(subjects);
        SubjectPersons.requirePersons(wanted, document.subjects(), persons);
        document.changeSubjects(wanted);
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

    @Override
    public DocumentSummary moveToTrash(Actor actor, DocumentId id, long expectedVersion) {
        access.requireMember(actor);
        Document document = load(id);
        if (document.isTrashed()) {
            return presenter.summarize(document);
        }
        requireVersion(document, expectedVersion);
        document.moveToTrash(actor.memberId(), clock.instant(), recoveryWindow);
        documents.save(document);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSummary restoreFromTrash(Actor actor, DocumentId id, long expectedVersion) {
        access.requireMember(actor);
        Document document = load(id);
        if (!document.isTrashed()) {
            return presenter.summarize(document);
        }
        requireVersion(document, expectedVersion);
        document.restoreFromTrash();
        documents.save(document);
        Document restored = load(id);
        if (restored.state() == ProcessingState.ARCHIVED) {
            // Archived while it was in the trash: the automatic analysis of the upload waited for this moment.
            analysis.archived(restored);
        }
        return presenter.summarize(load(id));
    }

    private Document loadVersion(DocumentId id, long expectedVersion) {
        Document document = load(id);
        requireVersion(document, expectedVersion);
        return document;
    }

    private static void requireVersion(Document document, long expectedVersion) {
        if (document.version() != expectedVersion) {
            throw new StaleVersionException("document", document.id().toString(), expectedVersion,
                    document.version());
        }
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
