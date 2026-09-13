package dev.aegis.documents.adapters.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.DocumentId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemOriginalStoreTest {
    @TempDir
    Path dir;

    @Test
    void storesAtomicallyWithChecksumAndSignature() throws IOException {
        FilesystemOriginalStore store = new FilesystemOriginalStore(dir.toString());
        byte[] pdf = "%PDF-1.7 hello".getBytes(StandardCharsets.US_ASCII);
        DocumentId id = DocumentId.random();

        var stored = store.store(id, new ByteArrayInputStream(pdf));

        assertThat(stored.stagingPath()).isEqualTo(id.value() + ".pdf");
        assertThat(stored.sizeBytes()).isEqualTo(pdf.length);
        assertThat(stored.pdfSignature()).isTrue();
        assertThat(stored.checksum().sha256Hex()).isEqualTo(sha256Hex(pdf));
        assertThat(store.read(stored.stagingPath())).isEqualTo(pdf);
        assertThat(store.listStagingPaths()).containsExactly(stored.stagingPath());
        assertThat(Files.list(dir).filter(p -> p.toString().endsWith(".part")).count()).isZero();
    }

    @Test
    void interruptedWriteLeavesNoFinalFile() throws IOException {
        FilesystemOriginalStore store = new FilesystemOriginalStore(dir.toString());
        InputStream failing = new InputStream() {
            private int count;

            @Override
            public int read() throws IOException {
                if (++count > 10) {
                    throw new IOException("connection reset");
                }
                return 'x';
            }
        };
        DocumentId id = DocumentId.random();

        assertThatThrownBy(() -> store.store(id, failing)).isInstanceOf(IOException.class);

        assertThat(store.exists(id.value() + ".pdf")).isFalse();
        assertThat(store.listStagingPaths()).isEmpty();
    }

    @Test
    void quarantineMovesOrphansAsideAndPathsCannotEscapeRoot() throws IOException {
        FilesystemOriginalStore store = new FilesystemOriginalStore(dir.toString());
        var stored = store.store(DocumentId.random(), new ByteArrayInputStream("%PDF-x".getBytes()));

        store.quarantineOrphan(stored.stagingPath());

        assertThat(store.listStagingPaths()).isEmpty();
        assertThat(Files.exists(dir.resolve(FilesystemOriginalStore.ORPHANS_DIR).resolve(stored.stagingPath())))
                .isTrue();
        assertThatThrownBy(() -> store.resolve("../outside.pdf")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
