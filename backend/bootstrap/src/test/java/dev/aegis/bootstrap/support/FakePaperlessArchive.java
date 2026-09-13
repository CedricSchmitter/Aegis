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
    public record Stored(int id, String title, byte[] bytes, String checksum) {
    }

    public volatile boolean available = true;
    public final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger nextId = new AtomicInteger(100);
    private final Map<Integer, Stored> documents = new ConcurrentHashMap<>();
    private final Map<String, TaskOutcome> tasks = new ConcurrentHashMap<>();
    private final Map<String, Integer> pollsRemaining = new ConcurrentHashMap<>();

    @Override
    public SubmissionOutcome submit(Path file, String title, String originalFilename) {
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
            documents.put(id, new Stored(id, title, bytes, checksum));
            tasks.put(taskId, new Completed(id));
        }
        pollsRemaining.put(taskId, 1);
        return new Accepted(taskId);
    }

    /** Registers a task that already finished, to simulate a submission whose result was never recorded. */
    public String registerCompletedTask(byte[] bytes, String title) {
        int id = nextId.getAndIncrement();
        documents.put(id, new Stored(id, title, bytes, sha256(bytes)));
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
        return new OriginalContent("paperless-" + paperlessDocumentId + ".pdf", "application/pdf", stored.bytes(),
                OriginalContent.SOURCE_ARCHIVE);
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
