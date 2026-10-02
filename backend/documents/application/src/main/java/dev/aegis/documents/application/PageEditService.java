package dev.aegis.documents.application;

import dev.aegis.documents.application.api.PageEditCommands;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.ScanStore;
import dev.aegis.documents.application.ports.ScanStore.StoredScan;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.pages.PageEdit;
import dev.aegis.documents.domain.pages.PageEditOutput;
import dev.aegis.documents.domain.pages.PageEditPlan;
import dev.aegis.documents.domain.pages.PageEditRepository;
import dev.aegis.documents.domain.pages.PageRef;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Accepts page editor requests (ADR-0024). Before acknowledging, it checks the plan against the current sources and
 * the limits, stores the newly scanned pages durably and commits the operation with its job; the job then creates
 * the documents. A request repeated with the same id and plan returns the stored operation.
 */
public class PageEditService implements PageEditCommands {
    private final PageEditSteps steps;
    private final PageEditRepository edits;
    private final DocumentRepository documents;
    private final PageSources sources;
    private final ScanStore scans;
    private final PdfPages pdf;
    private final CategoryRepository categories;
    private final HouseholdPersons persons;
    private final HouseholdAccess access;
    private final PageEditSettings settings;
    private final Clock clock;

    public PageEditService(PageEditSteps steps, PageEditRepository edits, DocumentRepository documents,
            PageSources sources, ScanStore scans, PdfPages pdf, CategoryRepository categories,
            HouseholdPersons persons, HouseholdAccess access, PageEditSettings settings, Clock clock) {
        this.steps = steps;
        this.edits = edits;
        this.documents = documents;
        this.sources = sources;
        this.scans = scans;
        this.pdf = pdf;
        this.categories = categories;
        this.persons = persons;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public PageEditView request(Actor actor, EditRequest request) {
        access.requireMember(actor);
        if (request.id() == null) {
            throw new InvalidInputException("validation.failed", "The operation id is required");
        }
        PageEditPlan plan = toPlan(request.outputs());
        Optional<PageEdit> existing = edits.findById(request.id());
        if (existing.isPresent()) {
            return repeated(existing.get(), plan);
        }
        if (request.declaredScanSize() > settings.maxOutputBytes()) {
            throw new InvalidInputException("document.tooLarge", "Scan exceeds " + settings.maxOutputBytes());
        }
        requireFoldersAndPersons(plan);
        Map<DocumentId, Integer> pageCounts = sourcePageCounts(plan);
        PageEdit.Scan scan = plan.usesScan() && request.scan() != null ? storeScan(request) : null;
        try {
            plan.validate(settings.limits(), pageCounts, scan == null ? 0 : scan.pageCount());
            PageEdit edit = PageEdit.requested(request.id(), actor.memberId(), clock.instant(), plan, scan);
            if (steps.record(edit)) {
                return view(edit);
            }
        } catch (RuntimeException failed) {
            deleteQuietly(scan);
            throw failed;
        }
        // A concurrent request with the same id was stored first.
        deleteQuietly(scan);
        return repeated(edits.findById(request.id()).orElseThrow(), plan);
    }

    @Override
    public PageEditView get(Actor actor, UUID id) {
        access.requireMember(actor);
        return view(edits.findById(id).orElseThrow(() -> new NotFoundException("pageEdit", id.toString())));
    }

    private PageEditView repeated(PageEdit existing, PageEditPlan plan) {
        if (!existing.sameRequest(plan)) {
            throw new InvalidInputException("pageEdit.conflict", "Operation " + existing.id()
                    + " was requested with a different plan");
        }
        return view(existing);
    }

    private static PageEditPlan toPlan(List<OutputRequest> outputs) {
        if (outputs == null) {
            throw new InvalidInputException("pageEdit.plan.empty", "The plan creates no document");
        }
        List<PageEditOutput> converted = new ArrayList<>();
        for (OutputRequest output : outputs) {
            List<PageRef> pages = new ArrayList<>();
            for (PageRequest page : output.pages() == null ? List.<PageRequest>of() : output.pages()) {
                pages.add(page.document() == null ? PageRef.ofScan(page.pageIndex(), page.rotation())
                        : PageRef.ofDocument(page.document(), page.pageIndex(), page.rotation()));
            }
            Set<Subject> subjects = Subjects.fromViews(output.subjects() == null ? List.of() : output.subjects());
            converted.add(new PageEditOutput(output.title(), output.titleChosen(), output.categoryId(), subjects,
                    pages));
        }
        return new PageEditPlan(converted);
    }

    private void requireFoldersAndPersons(PageEditPlan plan) {
        CategoryTree tree = null;
        for (PageEditOutput output : plan.outputs()) {
            if (output.categoryId() != null) {
                tree = tree == null ? categories.load() : tree;
                tree.require(new CategoryId(output.categoryId()));
            }
            SubjectPersons.requirePersons(output.subjects(), Set.of(), persons);
        }
    }

    private Map<DocumentId, Integer> sourcePageCounts(PageEditPlan plan) {
        Map<DocumentId, Integer> counts = new LinkedHashMap<>();
        if (plan.sourceDocuments().size() > settings.limits().maxSources()) {
            return counts; // validate() reports the limit; no source needs to be fetched for that
        }
        for (DocumentId id : plan.sourceDocuments()) {
            Document document = documents.findById(id)
                    .orElseThrow(() -> new NotFoundException("document", id.toString()));
            if (document.isTrashed()) {
                throw new InvalidInputException("pageEdit.source.trashed", "Document " + id + " is in the trash");
            }
            try {
                counts.put(id, sources.inspect(sources.resolve(document)).pageCount());
            } catch (PdfRefusedException refused) {
                throw new InvalidInputException(refused.code(), refused.getMessage());
            } catch (IOException e) {
                throw new UncheckedIOException("could not read the pages of " + id, e);
            }
        }
        return counts;
    }

    private PageEdit.Scan storeScan(EditRequest request) {
        StoredScan stored;
        try {
            stored = scans.store(request.id(), request.scan());
        } catch (IOException e) {
            throw new UncheckedIOException("could not store the scanned pages of " + request.id(), e);
        }
        try {
            if (stored.sizeBytes() > settings.maxOutputBytes()) {
                throw new InvalidInputException("document.tooLarge", "Scan exceeds " + settings.maxOutputBytes());
            }
            if (!stored.pdf()) {
                throw new InvalidInputException("pageEdit.scan.invalid", "Scanned pages must be sent as a PDF");
            }
            int pages = pdf.inspect(scans.resolve(stored.path())).pageCount();
            return new PageEdit.Scan(stored.path(), stored.checksum(), pages);
        } catch (PdfRefusedException refused) {
            deleteQuietly(stored.path());
            throw new InvalidInputException("pageEdit.scan.invalid", refused.getMessage());
        } catch (IOException e) {
            deleteQuietly(stored.path());
            throw new UncheckedIOException("could not read the scanned pages of " + request.id(), e);
        } catch (RuntimeException e) {
            deleteQuietly(stored.path());
            throw e;
        }
    }

    PageEditView view(PageEdit edit) {
        List<ResultView> results = new ArrayList<>();
        if (edit.state() == PageEdit.State.COMPLETED) {
            for (PageEdit.Result result : edit.results()) {
                documents.findById(result.document()).ifPresent(document -> results.add(new ResultView(
                        result.output(), document.id(), document.title(), result.existing(), document.isTrashed())));
            }
        } else {
            for (int output = 0; output < edit.plan().outputs().size(); output++) {
                int position = output;
                documents.findById(edit.resultIdFor(output)).ifPresent(document -> results.add(new ResultView(
                        position, document.id(), document.title(), false, document.isTrashed())));
            }
        }
        return new PageEditView(edit.id(), edit.state().name(), edit.failureCode().orElse(null), edit.requestedBy(),
                edit.requestedAt(), edit.finishedAt().orElse(null), results);
    }

    private void deleteQuietly(PageEdit.Scan scan) {
        if (scan != null) {
            deleteQuietly(scan.path());
        }
    }

    private void deleteQuietly(String path) {
        try {
            scans.delete(path);
        } catch (IOException ignored) {
            // A leftover scan file holds no record; it is only disk space.
        }
    }
}
