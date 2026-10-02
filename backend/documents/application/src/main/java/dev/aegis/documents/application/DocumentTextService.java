package dev.aegis.documents.application;

import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.PdfText;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.OriginalFormat;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A document's text page by page, read from the text layer of its PDF: the archive's searchable PDF, or a PDF original
 * when the archive keeps none, as for a born-digital PDF. The archive's OCR text without page boundaries is the
 * fallback for a document without a usable PDF, a PDF without any text, or one beyond the size bounds.
 *
 * <p>Texts are kept in memory for recently read documents, bounded in total characters, because one answer reads the
 * same document several times (reading in parts, then checking quotes). The key names the archive document and the
 * original's checksum, so a restored document is read again.
 */
public class DocumentTextService implements DocumentTextQuery {
    static final int MAX_PAGES = 1_000;
    static final int MAX_CHARACTERS = 2_000_000;
    static final long CACHED_CHARACTERS = 8_000_000;

    private record Extracted(List<String> pages, boolean paged, long characters) {
        static Extracted of(List<String> pages, boolean paged) {
            return new Extracted(List.copyOf(pages), paged, pages.stream().mapToLong(String::length).sum());
        }
    }

    private final DocumentRepository documents;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final PdfText pdf;
    private final HouseholdAccess access;
    private final LinkedHashMap<String, Extracted> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedCharacters;

    public DocumentTextService(DocumentRepository documents, PaperlessArchive archive, OriginalStore store,
            PdfText pdf, HouseholdAccess access) {
        this.documents = documents;
        this.archive = archive;
        this.store = store;
        this.pdf = pdf;
        this.access = access;
    }

    @Override
    public DocumentText text(Actor actor, DocumentId id) {
        access.requireMember(actor);
        Document document = documents.findById(id).orElseThrow(() -> new NotFoundException("document",
                id.toString()));
        document.requireNotTrashed();
        if (document.state() != ProcessingState.ARCHIVED || document.paperlessDocumentId().isEmpty()) {
            throw new InvalidInputException("document.notArchived", "The document is not archived yet");
        }
        int paperlessId = document.paperlessDocumentId().get();
        String key = id + "/" + paperlessId + "/" + document.checksum().sha256Hex();
        Extracted extracted = cached(key);
        if (extracted == null) {
            try {
                extracted = extract(document, paperlessId);
            } catch (ArchiveUnavailableException | ArchiveRejectedException unavailable) {
                throw new ContentUnavailableException("the archive text of " + id + " is unavailable", unavailable);
            }
            remember(key, extracted);
        }
        return new DocumentText(id, document.title(), extracted.pages(), extracted.paged());
    }

    private Extracted extract(Document document, int paperlessId) {
        Optional<byte[]> source = pdfOf(document, paperlessId);
        if (source.isPresent()) {
            try {
                Optional<List<String>> pages = pdf.pageTexts(source.get(), MAX_PAGES, MAX_CHARACTERS);
                if (pages.isPresent() && !pages.get().isEmpty()
                        && pages.get().stream().anyMatch(page -> !page.isBlank())) {
                    return Extracted.of(pages.get(), true);
                }
            } catch (PdfRefusedException unusable) {
                // The archive's own text still serves, only without pages.
            }
        }
        String content = archive.fetchContent(paperlessId);
        return Extracted.of(List.of(content == null ? "" : content), false);
    }

    /** The archive's searchable PDF, otherwise a PDF original whose bytes match its checksum. */
    private Optional<byte[]> pdfOf(Document document, int paperlessId) {
        Optional<byte[]> archived = archive.fetchArchivePdf(paperlessId);
        if (archived.isPresent()) {
            return archived;
        }
        if (OriginalFormat.ofContentType(document.contentType()).filter(f -> f == OriginalFormat.PDF).isEmpty()) {
            return Optional.empty();
        }
        if (document.isStagingPresent() && store.exists(document.stagingPath())) {
            try {
                byte[] staged = store.read(document.stagingPath());
                if (document.checksum().matches(staged)) {
                    return Optional.of(staged);
                }
            } catch (IOException unreadable) {
                // The archive's copy follows.
            }
        }
        byte[] original = archive.fetchOriginal(paperlessId).bytes();
        return document.checksum().matches(original) ? Optional.of(original) : Optional.empty();
    }

    private synchronized Extracted cached(String key) {
        return cache.get(key);
    }

    private synchronized void remember(String key, Extracted extracted) {
        if (extracted.characters() > MAX_CHARACTERS) {
            return;
        }
        Extracted replaced = cache.put(key, extracted);
        if (replaced != null) {
            cachedCharacters -= replaced.characters();
        }
        cachedCharacters += extracted.characters();
        Iterator<Map.Entry<String, Extracted>> eldest = cache.entrySet().iterator();
        while (cachedCharacters > CACHED_CHARACTERS) {
            cachedCharacters -= eldest.next().getValue().characters();
            eldest.remove();
        }
    }
}
