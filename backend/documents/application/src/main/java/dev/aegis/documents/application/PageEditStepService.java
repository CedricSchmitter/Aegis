package dev.aegis.documents.application;

import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationCommand;
import dev.aegis.documents.application.RegisterReceivedDocument.RegistrationResult;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.documents.domain.pages.PageEdit;
import dev.aegis.documents.domain.pages.PageEditRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The page editor's transactional steps; the composition root wraps every call in one transaction. */
public class PageEditStepService implements PageEditSteps {
    private final PageEditRepository edits;
    private final DerivationRepository derivations;
    private final RegisterReceivedDocument registrar;
    private final ProcessingScheduler scheduler;
    private final PageEditSettings settings;
    private final Clock clock;

    public PageEditStepService(PageEditRepository edits, DerivationRepository derivations,
            RegisterReceivedDocument registrar, ProcessingScheduler scheduler, PageEditSettings settings, Clock clock) {
        this.edits = edits;
        this.derivations = derivations;
        this.registrar = registrar;
        this.scheduler = scheduler;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean record(PageEdit edit) {
        if (!edits.insert(edit)) {
            return false;
        }
        scheduler.schedulePageEdit(edit.id(), clock.instant());
        return true;
    }

    @Override
    public Optional<PageEdit> pending(UUID id) {
        return edits.findById(id).filter(PageEdit::isPending);
    }

    @Override
    public RegistrationResult registerOutput(RegistrationCommand command, Derivation derivation) {
        RegistrationResult result = registrar.register(command);
        if (!result.duplicate() || result.document().id().equals(command.id())) {
            derivations.insertIfAbsent(derivation);
        }
        return result;
    }

    @Override
    public FailureDecision recordFailure(UUID id, String code, boolean retryable) {
        PageEdit edit = edits.findById(id).filter(PageEdit::isPending).orElse(null);
        if (edit == null) {
            return new FailureDecision(false, 0);
        }
        boolean retry = edit.recordFailure(code, retryable, settings.maxAttempts(), clock.instant());
        edits.save(edit);
        return new FailureDecision(retry, edit.attempts());
    }

    @Override
    public void complete(UUID id, List<PageEdit.Result> results) {
        PageEdit edit = edits.findById(id).filter(PageEdit::isPending).orElse(null);
        if (edit == null) {
            return;
        }
        edit.complete(results, clock.instant());
        edits.save(edit);
    }
}
