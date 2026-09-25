package dev.aegis.bootstrap.support;

import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.PaperlessArchive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for Paperless with the behaviors the flow depends on: asynchronous consumption, duplicate
 * detection by checksum, original retrieval, substring search and an outage switch. The HTTP mapping of the real
 * adapter is covered separately; the deployed stack is verified against real Paperless.
 */
public class FakePaperlessArchive implements PaperlessArchive {
    /**
     * {@code archivePdf} stands in for the searchable PDF Paperless generates from an image original or from a PDF
     * registered with {@link #scannedPdf}.
     */
    public record Stored(int id, String title, byte[] bytes, String checksum, String contentType, byte[] archivePdf) {
    }

    public volatile boolean available = true;
    /** Switches off only the OCR text, as when the archive stored a document but cannot deliver its text. */
    public volatile boolean contentAvailable = true;
    public final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger nextId = new AtomicInteger(100);
    private final Map<Integer, Stored> documents = new ConcurrentHashMap<>();
    private final Map<String, TaskOutcome> tasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> pollsRemaining = new ConcurrentHashMap<>();
    private final Map<String, String> textsByChecksum = new ConcurrentHashMap<>();
    private final java.util.Set<String> scannedChecksums = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> texts = new ConcurrentHashMap<>();
    public final AtomicInteger contentReads = new AtomicInteger();

    /**
     * OCR text the fake reports once a file with these bytes is archived. Without it, the text is the file's bytes
     * read as ISO-8859-1, which suits the synthetic text "PDFs" of the tests.
     */
    public void ocrText(byte[] bytes, String text) {
        textsByChecksum.put(sha256(bytes), text);
    }

    /**
     * Treats a PDF with these bytes as pages without a text layer of their own, such as scanned pages: like Paperless,
     * the fake then keeps a searchable archive version with the given OCR text.
     */
    public void scannedPdf(byte[] bytes, String text) {
        ocrText(bytes, text);
        scannedChecksums.add(sha256(bytes));
    }

    @Override
    public SubmissionOutcome submit(Path file, String title, String originalFilename, String contentType) {
        requireAvailable();
        submissions.incrementAndGet();
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String checksum = sha256(bytes);
        String taskId = UUID.randomUUID().toString();
        Integer existing = documents.values().stream().filter(d -> d.checksum().equals(checksum)).map(Stored::id)
                .findFirst().orElse(null);
        if (existing != null) {
            tasks.put(taskId, new Duplicate(existing));
        } else {
            int id = nextId.getAndIncrement();
            byte[] archivePdf = contentType.startsWith("image/") || scannedChecksums.contains(checksum)
                    ? ("%PDF-1.7 archive of " + checksum).getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                    : null;
            documents.put(id, new Stored(id, title, bytes, checksum, contentType, archivePdf));
            texts.put(id, textsByChecksum.getOrDefault(checksum,
                    new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)));
            tasks.put(taskId, new Completed(id));
        }
        pollsRemaining.put(taskId, 1);
        return new Accepted(taskId);
    }

    /** Registers a task that already finished, to simulate a submission whose result was never recorded. */
    public String registerCompletedTask(byte[] bytes, String title) {
        int id = nextId.getAndIncrement();
        documents.put(id, new Stored(id, title, bytes, sha256(bytes), "application/pdf", null));
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new Completed(id));
        pollsRemaining.put(taskId, 0);
        return taskId;
    }

    @Override
    public TaskOutcome checkTask(String taskId) {
        requireAvailable();
        int remaining = pollsRemaining.getOrDefault(taskId, 0);
        if (remaining > 0) {
            pollsRemaining.put(taskId, remaining - 1);
            return new Pending();
        }
        return tasks.getOrDefault(taskId, new Pending());
    }

    @Override
    public OriginalContent fetchOriginal(int paperlessDocumentId) {
        requireAvailable();
        Stored stored = documents.get(paperlessDocumentId);
        if (stored == null) {
            throw new ArchiveUnavailableException("no document " + paperlessDocumentId);
        }
        return new OriginalContent("paperless-" + paperlessDocumentId, stored.contentType(), stored.bytes(),
                OriginalContent.SOURCE_ARCHIVE);
    }

    @Override
    public java.util.Optional<byte[]> fetchArchivePdf(int paperlessDocumentId) {
        requireAvailable();
        Stored stored = documents.get(paperlessDocumentId);
        if (stored == null) {
            throw new ArchiveUnavailableException("no document " + paperlessDocumentId);
        }
        return java.util.Optional.ofNullable(stored.archivePdf());
    }

    @Override
    public String fetchContent(int paperlessDocumentId) {
        requireAvailable();
        contentReads.incrementAndGet();
        if (!contentAvailable) {
            throw new ArchiveUnavailableException("fake content outage");
        }
        if (!documents.containsKey(paperlessDocumentId)) {
            throw new ArchiveUnavailableException("no document " + paperlessDocumentId);
        }
        return texts.getOrDefault(paperlessDocumentId, "");
    }

    @Override
    public ArchivedText fetchArchivedText(int paperlessDocumentId) {
        String content = fetchContent(paperlessDocumentId);
        return new ArchivedText(content, documents.get(paperlessDocumentId).archivePdf() != null);
    }

    @Override
    public boolean exists(int paperlessDocumentId) {
        requireAvailable();
        return documents.containsKey(paperlessDocumentId);
    }

    @Override
    public List<Integer> searchContent(String query) {
        requireAvailable();
        String needle = query.toLowerCase(Locale.ROOT);
        List<Integer> hits = new ArrayList<>();
        for (Stored stored : documents.values()) {
            if (stored.title().toLowerCase(Locale.ROOT).contains(needle)
                    || new String(stored.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1)
                            .toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(stored.id());
            }
        }
        return hits;
    }

    @Override
    public ArchiveProbe probe() {
        return available ? new ArchiveProbe(true, "fake", null) : new ArchiveProbe(false, null, "fake outage");
    }

    public void clear() {
        documents.clear();
        texts.clear();
        tasks.clear();
        pollsRemaining.clear();
    }

    public int documentCount() {
        return documents.size();
    }

    private void requireAvailable() {
        if (!available) {
            throw new ArchiveUnavailableException("fake outage");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
