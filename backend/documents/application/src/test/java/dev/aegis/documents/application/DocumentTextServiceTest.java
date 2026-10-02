package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.ContentUnavailableException;
import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import dev.aegis.documents.application.ports.PdfRefusedException;
import dev.aegis.documents.application.ports.PdfText;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A document's text page by page from its PDF, with the archive's text as the fallback. */
class DocumentTextServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final MemberId LISA = MemberId.random();
    private static final Actor ACTOR = new Actor(LISA);

    private final InMemoryDocumentRepository documents = new InMemoryDocumentRepository();
    private final TestDoubles.FakeArchive archive = new TestDoubles.FakeArchive();
    private final InMemoryOriginalStore store = new InMemoryOriginalStore();
    private final FakePdfText pdf = new FakePdfText();
    private final DocumentTextService service = new DocumentTextService(documents, archive, store, pdf,
            new TestDoubles.MembersOnly(LISA));

    @Test
    void theArchivesSearchablePdfGivesTheTextOfEveryPage() {
        Document scan = archived("Mietvertrag", "image/jpeg", 11, bytes("photo"));
        archive.archivePdfs.put(11, bytes("archive pdf 11"));
        pdf.pages.put("archive pdf 11", List.of("Mietvertrag\nMieter: Lisa", "", "Kündigungsfrist 3 Monate"));
        archive.texts.put(11, "Mietvertrag Mieter: Lisa Kündigungsfrist 3 Monate");

        DocumentText text = service.text(ACTOR, scan.id());

        assertThat(text.paged()).isTrue();
        assertThat(text.title()).isEqualTo("Mietvertrag");
        assertThat(text.pages()).containsExactly("Mietvertrag\nMieter: Lisa", "", "Kündigungsfrist 3 Monate");
        assertThat(text.pageAt(text.text().indexOf("Kündigungsfrist"))).hasValue(3);
        assertThat(archive.contentReads).as("the archive's text without pages is not needed").isZero();
    }

    @Test
    void aBornDigitalPdfIsReadFromItsStagingCopyOrTheArchivesOriginal() {
        byte[] original = bytes("born digital");
        Document invoice = archived("Rechnung", "application/pdf", 12, original);
        pdf.pages.put("born digital", List.of("Rechnung Nr. 1", "Summe 40 EUR"));

        assertThat(service.text(ACTOR, invoice.id()).pages()).containsExactly("Rechnung Nr. 1", "Summe 40 EUR");
        assertThat(pdf.read).containsExactly("born digital");

        Document archivedOnly = archived("Rechnung Kopie", "application/pdf", 13, bytes("only archived"));
        store.delete(archivedOnly.stagingPath());
        archive.originals.put(13, bytes("only archived"));
        pdf.pages.put("only archived", List.of("Seite 1", "Seite 2"));
        assertThat(service.text(ACTOR, archivedOnly.id()).pages()).containsExactly("Seite 1", "Seite 2");
    }

    @Test
    void anArchiveOriginalThatDoesNotMatchTheChecksumIsNotUsed() {
        Document invoice = archived("Rechnung", "application/pdf", 14, bytes("expected"));
        store.delete(invoice.stagingPath());
        archive.originals.put(14, bytes("something else"));
        pdf.pages.put("something else", List.of("Fremder Text"));
        archive.texts.put(14, "Rechnung Summe 40 EUR");

        DocumentText text = service.text(ACTOR, invoice.id());

        assertThat(text.paged()).isFalse();
        assertThat(text.text()).isEqualTo("Rechnung Summe 40 EUR");
    }

    @Test
    void withoutAUsablePdfTheArchivesTextServesWithoutPages() {
        Document photo = archived("Foto", "image/jpeg", 21, bytes("photo 21"));
        archive.texts.put(21, "Foto Text");
        Document blank = archived("Leer", "application/pdf", 22, bytes("no text layer"));
        pdf.pages.put("no text layer", List.of(" ", ""));
        archive.texts.put(22, "OCR Text");
        Document broken = archived("Kaputt", "application/pdf", 23, bytes("broken"));
        pdf.refused.add("broken");
        archive.texts.put(23, "Kaputt Text");
        Document huge = archived("Riesig", "application/pdf", 24, bytes("too large"));
        pdf.tooLarge.add("too large");
        archive.texts.put(24, "Riesig Text");

        assertThat(service.text(ACTOR, photo.id())).isEqualTo(DocumentText.unpaged(photo.id(), "Foto", "Foto Text"));
        assertThat(service.text(ACTOR, blank.id()).text()).isEqualTo("OCR Text");
        assertThat(service.text(ACTOR, broken.id()).text()).isEqualTo("Kaputt Text");
        assertThat(service.text(ACTOR, huge.id()).text()).isEqualTo("Riesig Text");
        assertThat(List.of(blank, broken, huge)).allSatisfy(document ->
                assertThat(service.text(ACTOR, document.id()).paged()).isFalse());
        assertThat(pdf.limits).containsOnly(List.of(DocumentTextService.MAX_PAGES,
                DocumentTextService.MAX_CHARACTERS));
    }

    @Test
    void aDocumentReadAgainComesFromMemoryUntilLaterReadsDisplaceIt() {
        String big = "x".repeat(DocumentTextService.MAX_CHARACTERS / 4);
        List<Document> read = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Document document = archived("Akte " + i, "application/pdf", 30 + i, bytes("akte " + i));
            pdf.pages.put("akte " + i, List.of(big, big));
            read.add(document);
        }

        service.text(ACTOR, read.getFirst().id());
        service.text(ACTOR, read.getFirst().id());
        assertThat(pdf.read).hasSize(1);

        for (Document document : read) {
            service.text(ACTOR, document.id());
        }
        assertThat(pdf.read).as("8 million characters fit, so the ninth document displaces the first")
                .hasSize(9);
        service.text(ACTOR, read.getLast().id());
        service.text(ACTOR, read.getFirst().id());
        assertThat(pdf.read).hasSize(10);
    }

    @Test
    void theArchiveBeingUnreachableIsReportedAndNotRemembered() {
        Document scan = archived("Scan", "image/jpeg", 41, bytes("photo 41"));
        archive.archivePdfs.put(41, bytes("archive pdf 41"));
        pdf.pages.put("archive pdf 41", List.of("Seite"));
        archive.available = false;

        assertThatThrownBy(() -> service.text(ACTOR, scan.id())).isInstanceOf(ContentUnavailableException.class);

        archive.available = true;
        assertThat(service.text(ACTOR, scan.id()).pages()).containsExactly("Seite");
    }

    @Test
    void onlyMembersReadArchivedDocumentsOutsideTheTrash() {
        Document scan = archived("Scan", "image/jpeg", 51, bytes("photo 51"));
        Document pending = Document.received(DocumentId.random(), "Neu", "neu.pdf", "application/pdf", 10,
                InMemoryOriginalStore.sha256(bytes("neu")), LISA, NOW, Set.of(Subject.house()), "neu.pdf");
        documents.save(pending);

        assertThatThrownBy(() -> service.text(new Actor(MemberId.random()), scan.id()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.text(ACTOR, pending.id())).isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((InvalidInputException) e).code()).isEqualTo("document.notArchived"));
        scan.moveToTrash(Trash.begin(LISA, NOW, Trash.RecoveryWindow.DEFAULT));
        documents.save(scan);
        assertThatThrownBy(() -> service.text(ACTOR, scan.id())).isInstanceOf(TrashedException.class);
    }

    private Document archived(String title, String contentType, int paperlessId, byte[] original) {
        Document document;
        try {
            String path = store.store(DocumentId.random(), new ByteArrayInputStream(original)).stagingPath();
            document = Document.received(DocumentId.random(), title, title + ".pdf", contentType, original.length,
                    InMemoryOriginalStore.sha256(original), LISA, NOW.minusSeconds(60), Set.of(Subject.house()),
                    path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        document.markSubmitted("task-" + paperlessId, NOW.minusSeconds(30));
        document.markArchived(paperlessId, NOW);
        archive.originals.putIfAbsent(paperlessId, original);
        documents.save(document);
        return document;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Stands in for the PDF library: the "PDF" is a name, mapped to its pages. */
    private static final class FakePdfText implements PdfText {
        final Map<String, List<String>> pages = new HashMap<>();
        final Set<String> refused = new java.util.HashSet<>();
        final Set<String> tooLarge = new java.util.HashSet<>();
        final List<String> read = new ArrayList<>();
        final Set<List<Integer>> limits = new java.util.HashSet<>();

        @Override
        public Optional<List<String>> pageTexts(byte[] pdf, int maxPages, int maxCharacters) {
            String name = new String(pdf, StandardCharsets.UTF_8);
            read.add(name);
            limits.add(List.of(maxPages, maxCharacters));
            if (refused.contains(name)) {
                throw new PdfRefusedException(PdfRefusedException.UNREADABLE, "broken");
            }
            if (tooLarge.contains(name)) {
                return Optional.empty();
            }
            return Optional.of(pages.getOrDefault(name, List.of("")));
        }
    }
}
