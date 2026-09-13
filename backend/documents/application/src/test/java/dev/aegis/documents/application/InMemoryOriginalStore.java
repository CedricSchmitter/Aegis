package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps originals in memory; exposes the same contract as the filesystem adapter. */
public class InMemoryOriginalStore implements OriginalStore {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    final List<String> quarantined = new ArrayList<>();

    @Override
    public StoredOriginal store(DocumentId id, InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        String path = id + ".pdf";
        files.put(path, bytes);
        boolean pdf = bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                .equals("%PDF-");
        return new StoredOriginal(path, bytes.length, sha256(bytes), pdf);
    }

    @Override
    public boolean exists(String stagingPath) {
        return files.containsKey(stagingPath);
    }

    @Override
    public byte[] read(String stagingPath) throws IOException {
        byte[] bytes = files.get(stagingPath);
        if (bytes == null) {
            throw new IOException("missing " + stagingPath);
        }
        return bytes;
    }

    @Override
    public Path resolve(String stagingPath) {
        return Path.of("in-memory", stagingPath);
    }

    @Override
    public void delete(String stagingPath) {
        files.remove(stagingPath);
    }

    @Override
    public List<String> listStagingPaths() {
        return new ArrayList<>(files.keySet());
    }

    @Override
    public void quarantineOrphan(String stagingPath) {
        files.remove(stagingPath);
        quarantined.add(stagingPath);
    }

    public static Checksum sha256(byte[] bytes) {
        try {
            return new Checksum(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
