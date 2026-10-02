package dev.aegis.documents.adapters.filesystem;

import dev.aegis.documents.application.ports.ScanStore;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.OriginalFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Scanned pages of page editor requests in {@code page-edits/} below ingress (ADR-0024): on the persistent volume,
 * written to a temporary name, fsynced and atomically renamed like originals. The reconciliation of originals lists
 * only the files directly in ingress and therefore never quarantines these.
 */
@Component
public class FilesystemScanStore implements ScanStore {
    static final String DIRECTORY = "page-edits";

    private final Path root;

    public FilesystemScanStore(@Value("${aegis.ingress.dir}") String ingressDir) throws IOException {
        this.root = Path.of(ingressDir).toAbsolutePath().normalize().resolve(DIRECTORY);
        Files.createDirectories(root);
    }

    @Override
    public StoredScan store(UUID pageEdit, InputStream content) throws IOException {
        String name = pageEdit + "-" + UUID.randomUUID() + ".pdf";
        Path temp = root.resolve(name + ".part");
        MessageDigest digest = sha256();
        byte[] head = new byte[OriginalFormat.SIGNATURE_LENGTH];
        long size;
        int headLength;
        try (InputStream in = new DigestInputStream(content, digest);
                OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            headLength = in.readNBytes(head, 0, head.length);
            out.write(head, 0, headLength);
            size = headLength + in.transferTo(out);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        fsyncDirectory(root);
        boolean pdf = OriginalFormat.detect(Arrays.copyOf(head, headLength))
                .filter(format -> format == OriginalFormat.PDF).isPresent();
        return new StoredScan(DIRECTORY + "/" + name, size, new Checksum(HexFormat.of().formatHex(digest.digest())),
                pdf);
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(resolve(path));
    }

    @Override
    public Path resolve(String path) {
        Path resolved = root.getParent().resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("scan path escapes the page edit directory");
        }
        return resolved;
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Directory fsync is not supported on every filesystem (for example on Windows); the file itself is synced.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
