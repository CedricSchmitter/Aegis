package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ArchiveHealth;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentExportRecord;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentReference;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.NotFoundException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the shared collection. Not wrapped in a database transaction because archive calls happen inline.
 */
public class DocumentQueryService implements DocumentQueries, DocumentReferenceQuery, DocumentExportQuery,
        DocumentStatusQuery {
    private final DocumentRepository documents;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final DocumentPresenter presenter;
    private final HouseholdAccess access;
    private final Clock clock;

    public DocumentQueryService(DocumentRepository documents, PaperlessArchive archive, OriginalStore store,
            DocumentPresenter presenter, HouseholdAccess access, Clock clock) {
        this.documents = documents;
        this.archive = archive;
        this.store = store;
        this.presenter = presenter;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public DocumentSummary get(Actor actor, DocumentId id) {
        access.requireMember(actor);
        return presenter.summarize(load(id));
    }

    @Override
    public DocumentSearchResult search(Actor actor, String query) {
        access.requireMember(actor);
        long total = documents.findAll().size();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return new DocumentSearchResult(documents.findAll().stream().map(presenter::summarize).toList(),
                    ContentSearchState.NOT_APPLIED, total);
        }
        Map<DocumentId, Document> merged = new LinkedHashMap<>();
        documents.findByTitleContaining(trimmed).forEach(d -> merged.put(d.id(), d));
        ContentSearchState contentState;
        try {
            List<Integer> ids = archive.searchContent(trimmed);
            if (!ids.isEmpty()) {
                documents.findByPaperlessDocumentIds(ids).forEach(d -> merged.putIfAbsent(d.id(), d));
            }
            contentState = ContentSearchState.READY;
        } catch (ArchiveUnavailableException unavailable) {
            contentState = ContentSearchState.UNAVAILABLE;
        }
        List<Document> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(Document::uploadedAt).reversed());
        return new DocumentSearchResult(ordered.stream().map(presenter::summarize).toList(), contentState, total);
    }

    @Override
    public OriginalContent original(Actor actor, DocumentId id) {
        access.requireMember(actor);
        return originalOf(load(id));
    }

    @Override
    public Optional<DocumentReference> findReference(DocumentId id) {
        return documents.findById(id).map(d -> new DocumentReference(d.id(), d.title()));
    }

    @Override
    public List<DocumentExportRecord> exportAll(Actor actor) {
        access.requireMember(actor);
        return documents.findAll().stream().map(presenter::exportRecord).toList();
    }

    @Override
    public OriginalContent originalForExport(Actor actor, DocumentId id) {
        return original(actor, id);
    }

    @Override
    public ArchiveHealth archiveHealth(Actor actor) {
        access.requireMember(actor);
        PaperlessArchive.ArchiveProbe probe;
        try {
            probe = archive.probe();
        } catch (ArchiveUnavailableException e) {
            probe = new PaperlessArchive.ArchiveProbe(false, null, e.getMessage());
        }
        Instant lastArchival = documents.findByState(ProcessingState.ARCHIVED).stream()
                .map(d -> d.archivedAt().orElse(null)).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new ArchiveHealth(probe.reachable(), probe.serverVersion(), probe.detail(), clock.instant(),
                lastArchival);
    }

    @Override
    public Map<String, Long> processingCounts(Actor actor) {
        access.requireMember(actor);
        Map<ProcessingState, Long> counts = new EnumMap<>(ProcessingState.class);
        for (ProcessingState state : ProcessingState.values()) {
            counts.put(state, 0L);
        }
        for (Document document : documents.findAll()) {
            counts.merge(document.state(), 1L, Long::sum);
        }
        Map<String, Long> result = new LinkedHashMap<>();
        counts.forEach((state, count) -> result.put(state.name(), count));
        return result;
    }

    private OriginalContent originalOf(Document document) {
        if (document.state() == ProcessingState.ARCHIVED) {
            try {
                OriginalContent archived = archive.fetchOriginal(document.paperlessDocumentId().orElseThrow());
                return new OriginalContent(document.originalFilename(), document.contentType(), archived.bytes(),
                        OriginalContent.SOURCE_ARCHIVE);
            } catch (ArchiveUnavailableException unavailable) {
                if (!document.isStagingPresent()) {
                    throw new OriginalUnavailableException("archive unreachable and staging copy removed");
                }
            }
        }
        if (!document.isStagingPresent() || !store.exists(document.stagingPath())) {
            throw new OriginalUnavailableException("no copy of the original is available right now");
        }
        try {
            return new OriginalContent(document.originalFilename(), document.contentType(),
                    store.read(document.stagingPath()), OriginalContent.SOURCE_STAGING);
        } catch (IOException e) {
            throw new OriginalUnavailableException("staging copy could not be read: " + e.getMessage());
        }
    }

    private Document load(DocumentId id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("document", id.toString()));
    }
}
