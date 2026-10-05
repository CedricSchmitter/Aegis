package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.*;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.OriginalFormat;
import dev.aegis.documents.domain.text.PassageRevision;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/** One extraction path for document reads and indexing. Cache identity includes actual OCR and PDF bytes. */
public final class DocumentTextExtractor implements AutoCloseable {
    public static final int MAX_PAGES = 1_000;
    public static final int MAX_CHARACTERS = 2_000_000;
    public static final int MAX_BYTES = 50_000_000;
    public static final int DEADLINE_SECONDS = 40;
    private static final long CACHE_CHARACTERS = 8_000_000;
    private final PaperlessArchive archive;
    private final OriginalStore store;
    private final PdfText pdf;
    private final java.time.Duration deadline;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(2);
    private final LinkedHashMap<String, Text> cache = new LinkedHashMap<>(16, .75f, true);
    private long cachedCharacters;

    public record Text(List<String> pages, boolean paged, String fingerprint, String sourceKind) {
        public Text { pages = List.copyOf(pages); }
        public PassageRevision revision() { return PassageRevision.of(fingerprint, sourceKind, pages, paged); }
    }
    private record Source(String ocr, byte[] pdf, String kind, String fingerprint) { }
    public static final class Refused extends RuntimeException {
        private final String code;
        public Refused(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    public DocumentTextExtractor(PaperlessArchive archive, OriginalStore store, PdfText pdf) {
        this(archive,store,pdf,java.time.Duration.ofSeconds(DEADLINE_SECONDS));
    }
    DocumentTextExtractor(PaperlessArchive archive, OriginalStore store, PdfText pdf, java.time.Duration deadline) {
        this.archive = archive; this.store = store; this.pdf = pdf; this.deadline = deadline;
    }

    /** Source is captured again before returning; a changing source is retried instead of publishing mixed text. */
    public Text verified(Document document) {
        if (!capacity.tryAcquire()) { throw new Refused("text.busy"); }
        Future<Text> work = workers.submit(() -> {
            try {
                Source source = source(document);
                Text result = cached(source.fingerprint());
                if (result == null) { result = extract(source); }
                if (!source.fingerprint().equals(source(document).fingerprint())) {
                    throw new Refused("text.sourceChanged");
                }
                remember(result);
                return result;
            } finally { capacity.release(); }
        });
        try { return work.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { work.cancel(true); throw new Refused("text.deadline"); }
        catch (InterruptedException interrupted) {
            work.cancel(true); Thread.currentThread().interrupt(); throw new Refused("text.interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException failure) { throw failure; }
            throw new IllegalStateException(failed.getCause());
        }
    }

    private Source source(Document document) {
        int id = document.paperlessDocumentId().orElseThrow();
        String ocr = archive.fetchArchivedText(id).content();
        if (ocr == null) { ocr = ""; }
        if (ocr.length() > MAX_CHARACTERS) { throw new Refused("text.characterLimit"); }
        Optional<byte[]> bytes = archive.fetchArchivePdf(id);
        String kind = "ARCHIVE_PDF";
        if (bytes.isEmpty()) {
            kind = "ORIGINAL_PDF";
            if (OriginalFormat.ofContentType(document.contentType()).filter(f -> f == OriginalFormat.PDF).isPresent()) {
                if (document.isStagingPresent() && store.exists(document.stagingPath())) {
                    try {
                        byte[] staged = store.readForText(document.stagingPath(), MAX_BYTES);
                        if (document.checksum().matches(staged)) { bytes = Optional.of(staged); }
                    } catch (IOException unreadable) { /* Fall back to the archive's verified original. */ }
                }
                if (bytes.isEmpty()) {
                    byte[] original = archive.fetchTextOriginal(id).bytes();
                    if (document.checksum().matches(original)) { bytes = Optional.of(original); }
                }
            }
        }
        byte[] value = bytes.orElse(null);
        if (value != null && value.length > MAX_BYTES) { throw new Refused("text.byteLimit"); }
        if (value == null) { kind = "ARCHIVE_OCR"; }
        String fingerprint = PassageRevision.hash(id + "/" + document.checksum().sha256Hex() + "/" + kind + "/"
                + PassageRevision.hash(ocr) + "/" + (value == null ? "none" : PassageRevision.hash(value)));
        return new Source(ocr, value, kind, fingerprint);
    }

    private Text extract(Source source) {
        if (source.pdf() != null) {
            try {
                Optional<List<String>> pages = pdf.pageTexts(source.pdf(), MAX_PAGES, MAX_CHARACTERS);
                if (pages.isPresent() && !pages.get().isEmpty() && pages.get().stream().anyMatch(p -> !p.isBlank())) {
                    // Account for page separators, which are also original-text positions.
                    if (pages.get().stream().mapToLong(String::length).sum() + pages.get().size() - 1 > MAX_CHARACTERS) {
                        throw new Refused("text.characterLimit");
                    }
                    return new Text(pages.get(), true, source.fingerprint(), source.kind());
                }
            } catch (PdfRefusedException unreadable) { /* OCR remains a bounded, unpaged fallback. */ }
        }
        return new Text(List.of(source.ocr()), false, source.fingerprint(), "ARCHIVE_OCR");
    }

    private synchronized Text cached(String key) { return cache.get(key); }
    private synchronized void remember(Text text) {
        long size = text.pages().stream().mapToLong(String::length).sum();
        Text replaced = cache.put(text.fingerprint(), text);
        if (replaced != null) { cachedCharacters -= replaced.pages().stream().mapToLong(String::length).sum(); }
        cachedCharacters += size;
        var entries = cache.entrySet().iterator();
        while (cachedCharacters > CACHE_CHARACTERS || cache.size() > 128) {
            cachedCharacters -= entries.next().getValue().pages().stream().mapToLong(String::length).sum();
            entries.remove();
        }
    }
    @Override public void close() { workers.shutdownNow(); }
}
