package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.PageCache;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.ScanStore;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.documents.domain.pages.PageEdit;
import dev.aegis.documents.domain.pages.PageEditRepository;
import dev.aegis.shared.DocumentId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Doubles for the page editor. A fake PDF is a text file such as {@code %PDF-fake pages=3 name=letter}; the fake
 * library reads its page count from it and writes outputs that name their placements, so the bytes are deterministic
 * like the real writer's.
 */
final class PageEditDoubles {
    private PageEditDoubles() {
    }

    static byte[] fakePdf(String name, int pages) {
        return ("%PDF-fake pages=" + pages + " name=" + name).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] encryptedPdf() {
        return "%PDF-fake encrypted".getBytes(StandardCharsets.US_ASCII);
    }

    static final class FakePdf implements PdfPages {
        final List<List<Placement>> assembled = new ArrayList<>();
        int renders;

        @Override
        public Inspection inspect(Path pdf) throws IOException {
            String content = Files.readString(pdf, StandardCharsets.US_ASCII);
            if (content.contains("encrypted")) {
                throw new PdfRefusedException(PdfRefusedException.ENCRYPTED, "needs a password");
            }
            int start = content.indexOf("pages=");
            if (!content.startsWith("%PDF-") || start < 0) {
                throw new PdfRefusedException(PdfRefusedException.UNREADABLE, "not a fake PDF");
            }
            int count = Integer.parseInt(content.substring(start + 6).split(" ")[0]);
            List<PageSize> sizes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                sizes.add(new PageSize(595, 842));
            }
            return new Inspection(sizes, content.contains("signed"), content.contains("attachment"));
        }

        @Override
        public byte[] renderPreview(Path pdf, int pageIndex, int longSidePixels) {
            renders++;
            return ("jpeg " + pdf.getFileName() + " " + pageIndex + " " + longSidePixels)
                    .getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public void assemble(Map<String, Path> sources, List<Placement> pages, byte[] fileId, Path target)
                throws IOException {
            for (Placement page : pages) {
                if (!sources.containsKey(page.source())) {
                    throw new IllegalArgumentException("no source " + page.source());
                }
            }
            assembled.add(List.copyOf(pages));
            Files.writeString(target, "%PDF-fake pages=" + pages.size() + " " + pages + " id="
                    + HexFormat.of().formatHex(fileId), StandardCharsets.US_ASCII);
        }
    }

    static final class DirectoryCache implements PageCache {
        private final Path root;
        final Map<String, byte[]> previews = new HashMap<>();
        int sourcesStored;

        DirectoryCache(Path root) {
            this.root = root;
        }

        @Override
        public Optional<Path> findSource(String key) {
            Path file = root.resolve(key + ".pdf");
            return Files.exists(file) ? Optional.of(file) : Optional.empty();
        }

        @Override
        public Path storeSource(String key, byte[] content) throws IOException {
            sourcesStored++;
            return Files.write(root.resolve(key + ".pdf"), content);
        }

        @Override
        public Optional<byte[]> findPreview(String key, int pageIndex, int longSidePixels) {
            return Optional.ofNullable(previews.get(key + "/" + pageIndex + "/" + longSidePixels));
        }

        @Override
        public void storePreview(String key, int pageIndex, int longSidePixels, byte[] jpeg) {
            previews.put(key + "/" + pageIndex + "/" + longSidePixels, jpeg);
        }

        @Override
        public Path newWorkFile() throws IOException {
            return Files.createTempFile(root, "work", ".pdf");
        }
    }

    static final class DirectoryScans implements ScanStore {
        private final Path root;

        DirectoryScans(Path root) {
            this.root = root;
        }

        @Override
        public StoredScan store(UUID pageEdit, InputStream content) throws IOException {
            byte[] bytes = content.readAllBytes();
            String path = pageEdit + "-" + UUID.randomUUID() + ".pdf";
            Files.write(root.resolve(path), bytes);
            boolean pdf = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.US_ASCII).equals("%PDF-");
            return new StoredScan(path, bytes.length, InMemoryOriginalStore.sha256(bytes), pdf);
        }

        @Override
        public boolean exists(String path) {
            return Files.exists(root.resolve(path));
        }

        @Override
        public Path resolve(String path) {
            return root.resolve(path);
        }

        @Override
        public void delete(String path) throws IOException {
            Files.deleteIfExists(root.resolve(path));
        }

        long files() throws IOException {
            try (var stream = Files.list(root)) {
                return stream.count();
            }
        }
    }

    static final class InMemoryPageEdits implements PageEditRepository {
        final Map<UUID, PageEdit> edits = new LinkedHashMap<>();

        @Override
        public Optional<PageEdit> findById(UUID id) {
            return Optional.ofNullable(edits.get(id)).map(InMemoryPageEdits::copy);
        }

        @Override
        public boolean insert(PageEdit edit) {
            return edits.putIfAbsent(edit.id(), copy(edit)) == null;
        }

        @Override
        public void save(PageEdit edit) {
            edits.put(edit.id(), copy(edit));
        }

        private static PageEdit copy(PageEdit edit) {
            return PageEdit.rehydrate(edit.id(), edit.requestedBy(), edit.requestedAt(), edit.plan(),
                    edit.scan().orElse(null), edit.state(), edit.attempts(), edit.failureCode().orElse(null),
                    edit.finishedAt().orElse(null), edit.results(), edit.version());
        }
    }

    static final class InMemoryDerivations implements DerivationRepository {
        final Map<DocumentId, Derivation> derivations = new LinkedHashMap<>();

        @Override
        public void insertIfAbsent(Derivation derivation) {
            derivations.putIfAbsent(derivation.document(), derivation);
        }

        @Override
        public Optional<Derivation> findByDocument(DocumentId document) {
            return Optional.ofNullable(derivations.get(document));
        }

        @Override
        public List<Derivation> findBySource(DocumentId source) {
            return derivations.values().stream().filter(derivation -> derivation.sources().contains(source))
                    .toList();
        }

        /** What permanently deleting a source does to the ranges that name it. */
        void sourceDeleted(DocumentId source) {
            derivations.replaceAll((document, derivation) -> new Derivation(document, derivation.pageEdit(),
                    derivation.createdBy(), derivation.createdAt(), derivation.ranges().stream()
                            .map(range -> source.equals(range.source()) ? new Derivation.Range(range.firstPage(),
                                    range.lastPage(), range.kind(), null, range.firstSourcePage(),
                                    range.lastSourcePage(), range.rotation()) : range)
                            .toList()));
        }
    }
}
