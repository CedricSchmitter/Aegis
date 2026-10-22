package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.SimilarDocumentQueries.SimilarPair;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.Trash;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Near-duplicate hints from the stored fingerprints (ADR-0028). */
class SimilarDocumentServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final MemberId LISA = MemberId.random();
    private static final String LETTER = """
            Sehr geehrte Frau Beispiel, wir bestätigen den Eingang Ihrer Kündigung vom 03.09.2026 für den
            Stromliefervertrag 4711-0815. Der Vertrag endet zum 31.12.2026. Ihre Schlussrechnung erhalten Sie
            innerhalb von sechs Wochen nach dem Vertragsende an die uns bekannte Anschrift Musterweg 7 in
            12345 Musterstadt. Ein Guthaben überweisen wir auf das Konto mit der Endung 4421.
            """;
    private static final String OTHER = """
            Ihre Hausratversicherung VS-2026-004711: Ab dem 01.05.2026 beträgt Ihr Jahresbeitrag 187,40 Euro statt
            bisher 172,10 Euro. Die Versicherungssumme von 65.000 Euro bleibt unverändert. Sie können innerhalb eines
            Monats nach Zugang dieser Mitteilung kündigen; eine Kündigung muss uns in Textform erreichen.
            """;

    private InMemoryDocumentRepository documents;
    private InMemoryArchiveTextCheckRepository checks;
    private SimilarDocumentService service;
    private final Actor lisa = new Actor(LISA);

    @BeforeEach
    void setUp() {
        documents = new InMemoryDocumentRepository();
        checks = new InMemoryArchiveTextCheckRepository(documents);
        var views = new ClassificationViews(new InMemoryClassificationRepository(), new InMemoryCategoryRepository(),
                new InMemoryLabelRepository(), new InMemoryPlacementSuggestionRepository());
        service = new SimilarDocumentService(documents, checks,
                TestDoubles.presenter(new TestDoubles.RecordingScheduler(), views, checks),
                new TestDoubles.MembersOnly(LISA));
    }

    @Test
    void theSameLetterScannedTwiceIsAHintBothWaysAndOtherDocumentsAreNot() {
        Document scan = archived("Kündigungsbestätigung", 1, LETTER, NOW.minusSeconds(300));
        Document photo = archived("Foto 2026-10-20", 2, LETTER.replace("\n", " ").replace("Guthaben", "Gulhaben"),
                NOW.minusSeconds(200));
        Document other = archived("Beitragsanpassung", 3, OTHER, NOW.minusSeconds(100));

        assertThat(service.similarTo(lisa, scan.id())).extracting(DocumentSummary::id).containsExactly(photo.id());
        assertThat(service.similarTo(lisa, photo.id())).extracting(DocumentSummary::id).containsExactly(scan.id());
        assertThat(service.similarTo(lisa, other.id())).isEmpty();
        assertThat(service.similarPairs(lisa)).containsExactly(
                new SimilarPair(scan.id(), "Kündigungsbestätigung", photo.id(), "Foto 2026-10-20"));
    }

    @Test
    void aTrashedDocumentAndADocumentWithoutAFingerprintGiveNoHint() {
        Document scan = archived("Scan", 1, LETTER, NOW.minusSeconds(300));
        Document photo = archived("Foto", 2, LETTER, NOW.minusSeconds(200));
        Document unchecked = archived("Ungeprüft", 3, null, NOW.minusSeconds(100));

        photo.moveToTrash(Trash.begin(LISA, NOW, Trash.RecoveryWindow.DEFAULT));
        documents.save(photo);

        assertThat(service.similarTo(lisa, scan.id())).isEmpty();
        assertThat(service.similarTo(lisa, photo.id())).isEmpty();
        assertThat(service.similarTo(lisa, unchecked.id())).isEmpty();
        assertThat(service.similarPairs(lisa)).isEmpty();
    }

    @Test
    void textsTooShortToCompareGiveNoHint() {
        Document first = archived("Bon", 1, "Bäckerei Korn 2,40 EUR", NOW.minusSeconds(300));
        archived("Bon 2", 2, "Bäckerei Korn 2,40 EUR", NOW.minusSeconds(200));

        assertThat(service.similarTo(lisa, first.id())).isEmpty();
        assertThat(service.similarPairs(lisa)).isEmpty();
    }

    @Test
    void onlyMembersAskAndAnUnknownDocumentIsNotFound() {
        Document scan = archived("Scan", 1, LETTER, NOW.minusSeconds(300));

        assertThatThrownBy(() -> service.similarTo(new Actor(MemberId.random()), scan.id()))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.similarPairs(new Actor(MemberId.random())))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.similarTo(lisa, DocumentId.random())).isInstanceOf(NotFoundException.class);
    }

    /** An archived document whose text check measured {@code text}; none when {@code text} is null. */
    private Document archived(String title, int paperlessId, String text, Instant uploadedAt) {
        Document document = Document.received(DocumentId.random(), title, title + ".pdf", "application/pdf", 10,
                new Checksum(String.format("%064x", paperlessId)), LISA, uploadedAt, Set.of(Subject.house()),
                title + ".pdf");
        document.markSubmitted("task-" + paperlessId, uploadedAt.plusSeconds(5));
        document.markArchived(paperlessId, uploadedAt.plusSeconds(10));
        documents.save(document);
        if (text != null) {
            checks.save(ArchiveTextCheck.measured(document.id(), paperlessId, text, false, uploadedAt.plusSeconds(15)));
        }
        return document;
    }
}
