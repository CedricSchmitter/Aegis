package dev.aegis.documents.adapters.filesystem;

import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ingress storage on the persistent data volume. Files are written to a temporary name, fsynced, then atomically
 * renamed so that a crash never leaves a half-written file under the final name.
 */
@Component
public class FilesystemOriginalStore implements OriginalStore {
    static final String ORPHANS_DIR = "orphans";
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final Path root;

    public FilesystemOriginalStore(@Value("${aegis.ingress.dir}") String rootDir) throws IOException {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve(ORPHANS_DIR));
    }

    @Override
    public StoredOriginal store(DocumentId id, InputStream content) throws IOException {
        String finalName = id.value() + ".pdf";
        Path temp = root.resolve(finalName + ".part");
        Path target = root.resolve(finalName);
        MessageDigest digest = sha256();
        long size;
        byte[] head = new byte[PDF_SIGNATURE.length];
        int headLength;
        try (InputStream in = new DigestInputStream(content, digest);
                OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            headLength = in.readNBytes(head, 0, head.length);
            out.write(head, 0, headLength);
            size = headLength + in.transferTo(out);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        fsyncDirectory(root);
        boolean pdf = headLength == PDF_SIGNATURE.length && java.util.Arrays.equals(head, PDF_SIGNATURE);
        return new StoredOriginal(finalName, size, new Checksum(HexFormat.of().formatHex(digest.digest())), pdf);
    }

    @Override
    public boolean exists(String stagingPath) {
        return Files.isRegularFile(resolve(stagingPath));
    }

    @Override
    public byte[] read(String stagingPath) throws IOException {
        return Files.readAllBytes(resolve(stagingPath));
    }

    @Override
    public Path resolve(String stagingPath) {
        Path resolved = root.resolve(stagingPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("staging path escapes ingress root");
        }
        return resolved;
    }

    @Override
    public void delete(String stagingPath) throws IOException {
        Files.deleteIfExists(resolve(stagingPath));
    }

    @Override
    public List<String> listStagingPaths() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(name -> !name.endsWith(".part")).sorted().toList();
        }
    }

    @Override
    public void quarantineOrphan(String stagingPath) throws IOException {
        Path source = resolve(stagingPath);
        Path target = root.resolve(ORPHANS_DIR).resolve(source.getFileName());
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
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
