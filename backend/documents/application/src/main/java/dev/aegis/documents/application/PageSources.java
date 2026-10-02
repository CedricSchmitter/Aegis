package dev.aegis.documents.application;

import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PageCache;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfPages.Inspection;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.OriginalFormat;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The PDF a document contributes pages from (ADR-0024): a PDF original itself, the staging copy while it is present
 * and otherwise the archive's copy, or for a photo the searchable PDF the archive renders from it. The file is fetched
 * once into the page cache; concurrent requests for the same document wait for the first fetch instead of repeating it.
 */
public class PageSources {
    public static final String NOT_ARCHIVED = "pageEdit.source.notArchived";
    private static final int INSPECTIONS = 64;

    public enum Kind { PDF, PHOTO }

    /** A usable source; {@code key} names it in the page cache and is derived from the original's checksum. */
    public record Source(DocumentId document, Kind kind, String key, Path file, Checksum checksum) {
    }

    private final OriginalStore store;
    private final PaperlessArchive archive;
    private final PageCache cache;
    private final PdfPages pdf;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Inspection> inspections = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Inspection> eldest) {
                    return size() > INSPECTIONS;
                }
            });

    public PageSources(OriginalStore store, PaperlessArchive archive, PageCache cache, PdfPages pdf) {
        this.store = store;
        this.archive = archive;
        this.cache = cache;
        this.pdf = pdf;
    }

    public static Kind kindOf(Document document) {
        return OriginalFormat.ofContentType(document.contentType()).filter(OriginalFormat::isImage).isPresent()
                ? Kind.PHOTO : Kind.PDF;
    }

    /**
     * Resolves the source file of a document.
     *
     * @throws PdfRefusedException {@value #NOT_ARCHIVED} for a photo the archive has not rendered yet
     * @throws ArchiveUnavailableException when the archive copy is needed and cannot be read right now
     * @throws OriginalUnavailableException when no copy of the original is available
     */
    public Source resolve(Document document) throws IOException {
        Kind kind = kindOf(document);
        if (kind == Kind.PHOTO && document.state() != ProcessingState.ARCHIVED) {
            throw new PdfRefusedException(NOT_ARCHIVED, "photo " + document.id() + " is not archived yet");
        }
        String key = (kind == Kind.PHOTO ? "rendition-" : "original-") + document.checksum().sha256Hex();
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            Path file = cache.findSource(key).orElse(null);
            if (file == null) {
                byte[] content = kind == Kind.PHOTO ? rendition(document) : original(document);
                file = cache.storeSource(key, content);
            }
            return new Source(document.id(), kind, key, file, document.checksum());
        }
    }

    /** Checks the source and reads its pages; the result is remembered for recently used sources. */
    public Inspection inspect(Source source) throws IOException {
        Inspection known = inspections.get(source.key());
        if (known != null) {
            return known;
        }
        Inspection inspection = pdf.inspect(source.file());
        inspections.put(source.key(), inspection);
        return inspection;
    }

    private byte[] original(Document document) throws IOException {
        if (document.isStagingPresent() && store.exists(document.stagingPath())) {
            byte[] staged = store.read(document.stagingPath());
            if (document.checksum().matches(staged)) {
                return staged;
            }
        }
        if (document.state() != ProcessingState.ARCHIVED) {
            throw new OriginalUnavailableException("no copy of the original is available right now");
        }
        byte[] archived = archive.fetchOriginal(document.paperlessDocumentId().orElseThrow()).bytes();
        if (!document.checksum().matches(archived)) {
            throw new ArchiveUnavailableException("Archive original checksum mismatch");
        }
        return archived;
    }

    private byte[] rendition(Document document) {
        return archive.fetchArchivePdf(document.paperlessDocumentId().orElseThrow())
                .orElseThrow(() -> new PdfRefusedException(NOT_ARCHIVED,
                        "the archive keeps no rendition of photo " + document.id()));
    }
}
