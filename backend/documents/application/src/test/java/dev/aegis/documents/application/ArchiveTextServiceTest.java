package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.documents.application.ArchiveTextService.Outcome;
import dev.aegis.documents.application.ArchiveTextService.SweepReport;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.TextRecognitionView;
import dev.aegis.documents.domain.Checksum;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.text.ArchiveTextCheck;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Whether the archive recognized text (issue #87, ADR-0012): measured after archival, caught up by the sweep. */
class ArchiveTextServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final String LETTER = "Sehr geehrte Frau Muster, anbei erhalten Sie die Rechnung für September.";

    private final MutableClock clock = new MutableClock(NOW);
    private InMemoryDocumentRepository documents;
    private InMemoryArchiveTextCheckRepository checks;
    private TestDoubles.FakeArchive archive;
    private DocumentPresenter presenter;
    private ArchiveTextService service;

    @BeforeEach
    void setUp() {
        documents = new InMemoryDocumentRepository();
        checks = new InMemoryArchiveTextCheckRepository(documents);
        archive = new TestDoubles.FakeArchive();
        var classifications = new InMemoryClassificationRepository();
        var views = new ClassificationViews(classifications, new InMemoryCategoryRepository(),
                new InMemoryLabelRepository(), new InMemoryPlacementSuggestionRepository());
        presenter = TestDoubles.presenter(new TestDoubles.RecordingScheduler(), views, checks);
        service = new ArchiveTextService(documents, checks, archive, 2, clock);
    }

    @Test
    void anArchivedPhotoWithoutTextIsShownAsSuchIndependentOfAi() {
        Document photo = archived("Foto 2026-09-23 10:15", 11, NOW.minusSeconds(5));
        archive.texts.put(11, "  \n ");
        assertThat(view(photo).state()).isEqualTo(TextRecognitionView.PENDING);

        assertThat(service.check(photo.id())).isEqualTo(Outcome.MEASURED);

        TextRecognitionView view = view(photo);
        assertThat(view.state()).isEqualTo(TextRecognitionView.NONE);
        assertThat(view.recognizedCharacters()).isZero();
        assertThat(view.checkedAt()).isEqualTo(NOW);
        assertThat(view.lacksText()).isTrue();
        // Derived data only: the document itself is untouched.
        assertThat(documents.findById(photo.id()).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED);
    }

    @Test
    void littleAndEnoughTextAreDistinguished() {
        Document skewed = archived("Schief", 12, NOW.minusSeconds(20));
        Document straight = archived("Gerade", 13, NOW.minusSeconds(10));
        archive.texts.put(12, "Sehr geehrte Frau");
        archive.texts.put(13, LETTER);

        service.check(skewed.id());
        service.check(straight.id());

        assertThat(view(skewed).state()).isEqualTo(TextRecognitionView.LITTLE);
        assertThat(view(skewed).recognizedCharacters()).isEqualTo(15);
        assertThat(view(straight).state()).isEqualTo(TextRecognitionView.RECOGNIZED);
        assertThat(view(straight).lacksText()).isFalse();
    }

    @Test
    void aDocumentThatIsNotArchivedHasNoTextToCheck() {
        Document received = Document.received(DocumentId.random(), "Neu", "neu.jpg", "image/jpeg", 10,
                new Checksum("c".repeat(64)), MemberId.random(), NOW, Set.of(Subject.household()), "neu.jpg");
        documents.save(received);

        assertThat(service.check(received.id())).isEqualTo(Outcome.NOT_ARCHIVED);
        assertThat(service.check(DocumentId.random())).isEqualTo(Outcome.NOT_ARCHIVED);
        assertThat(checks.checks).isEmpty();
        assertThat(archive.contentReads).isZero();
        assertThat(view(received).state()).isEqualTo(TextRecognitionView.NOT_ARCHIVED);
    }

    @Test
    void anUnreadableArchiveIsRecordedAndNeverThrown() {
        Document photo = archived("Foto", 14, NOW.minusSeconds(5));
        archive.available = false;

        assertThat(service.check(photo.id())).isEqualTo(Outcome.FAILED);

        TextRecognitionView view = view(photo);
        assertThat(view.state()).isEqualTo(TextRecognitionView.UNAVAILABLE);
        assertThat(view.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(view.lacksText()).isFalse();
        assertThat(documents.findById(photo.id()).orElseThrow().state()).isEqualTo(ProcessingState.ARCHIVED);
    }

    @Test
    void aMeasuredDocumentIsNotReadAgain() {
        Document photo = archived("Foto", 15, NOW.minusSeconds(5));
        service.check(photo.id());

        assertThat(service.check(photo.id())).isEqualTo(Outcome.ALREADY_MEASURED);
        assertThat(archive.contentReads).isEqualTo(1);
    }

    @Test
    void theSweepCatchesUpNewestFirstInBoundedBatchesAndContinues() {
        Document oldest = archived("Alt", 21, NOW.minus(Duration.ofDays(30)));
        Document older = archived("Älter", 22, NOW.minus(Duration.ofDays(20)));
        Document newest = archived("Neu", 23, NOW.minus(Duration.ofDays(1)));
        archive.texts.put(21, LETTER);
        archive.texts.put(22, "");
        archive.texts.put(23, LETTER);

        assertThat(service.sweep()).isEqualTo(new SweepReport(2, 0));
        assertThat(checks.checks.keySet()).containsExactlyInAnyOrder(newest.id(), older.id());

        assertThat(service.sweep()).isEqualTo(new SweepReport(1, 0));
        assertThat(view(oldest).state()).isEqualTo(TextRecognitionView.RECOGNIZED);
        assertThat(view(older).state()).isEqualTo(TextRecognitionView.NONE);

        assertThat(service.sweep()).isEqualTo(new SweepReport(0, 0));
        assertThat(archive.contentReads).isEqualTo(3);
    }

    @Test
    void theSweepStopsAtTheFirstFailureAndRetriesTheDocumentOnlyWhenDue() {
        Document first = archived("Erstes", 31, NOW.minusSeconds(10));
        Document second = archived("Zweites", 32, NOW.minusSeconds(20));
        archive.texts.put(32, LETTER);
        archive.available = false;

        assertThat(service.sweep()).isEqualTo(new SweepReport(0, 1));
        assertThat(view(first).state()).isEqualTo(TextRecognitionView.UNAVAILABLE);
        assertThat(view(second).state()).isEqualTo(TextRecognitionView.PENDING);

        // The failed document waits for its next attempt; the others are not held up by it.
        archive.available = true;
        assertThat(service.sweep()).isEqualTo(new SweepReport(1, 0));
        assertThat(view(first).state()).isEqualTo(TextRecognitionView.UNAVAILABLE);
        assertThat(view(second).state()).isEqualTo(TextRecognitionView.RECOGNIZED);

        clock.advance(Duration.ofMinutes(5));
        assertThat(service.sweep()).isEqualTo(new SweepReport(1, 0));
        assertThat(view(first).state()).isEqualTo(TextRecognitionView.NONE);
    }

    @Test
    void theSearchablePdfIsOfferedOnlyWhenTheArchiveKeepsAnArchiveVersion() {
        Document scanned = archived("Scan", 41, NOW.minusSeconds(10));
        Document bornDigital = archived("Rechnung", 42, NOW.minusSeconds(20));
        archive.texts.put(41, LETTER);
        archive.texts.put(42, LETTER);
        archive.archivePdfs.put(41, "%PDF-1.7 archive".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(summary(scanned).archivePdfAvailable()).as("not checked yet").isFalse();

        service.check(scanned.id());
        service.check(bornDigital.id());

        assertThat(summary(scanned).archivePdfAvailable()).isTrue();
        assertThat(summary(bornDigital).archivePdfAvailable()).isFalse();
        assertThat(summary(bornDigital).textRecognition().state()).isEqualTo(TextRecognitionView.RECOGNIZED);

        // A check of another archive document (as after a restore) says nothing about the current one.
        Document restored = archived("Wiederhergestellt", 43, NOW.minusSeconds(30));
        checks.save(ArchiveTextCheck.measured(restored.id(), 41, LETTER, true, NOW));
        assertThat(summary(restored).archivePdfAvailable()).isFalse();
    }

    @Test
    void checksStoredBeforeTheArchiveVersionWasRecordedAreReadAgainAndKeepTheirCountMeanwhile() {
        Document scanned = archived("Scan", 51, NOW.minusSeconds(10));
        archive.texts.put(51, LETTER);
        archive.archivePdfs.put(51, "%PDF-1.7 archive".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        // As the migration leaves them: the count, no archive version, due at once.
        checks.save(new ArchiveTextCheck(scanned.id(), 51, 60, null, NOW.minusSeconds(5), 0, NOW.minusSeconds(5)));
        assertThat(summary(scanned).textRecognition().state()).isEqualTo(TextRecognitionView.RECOGNIZED);
        assertThat(summary(scanned).archivePdfAvailable()).isFalse();

        archive.available = false;
        assertThat(service.sweep()).isEqualTo(new SweepReport(0, 1));
        assertThat(summary(scanned).textRecognition().state()).as("a failed re-read keeps the count")
                .isEqualTo(TextRecognitionView.RECOGNIZED);

        archive.available = true;
        clock.advance(Duration.ofMinutes(5));
        assertThat(service.sweep()).isEqualTo(new SweepReport(1, 0));
        assertThat(summary(scanned).archivePdfAvailable()).isTrue();
        assertThat(service.sweep()).isEqualTo(new SweepReport(0, 0));
    }

    private Document archived(String title, int paperlessId, Instant archivedAt) {
        Document document = Document.received(DocumentId.random(), title, title + ".jpg", "image/jpeg", 10,
                new Checksum(String.format("%064x", paperlessId)), MemberId.random(), archivedAt.minusSeconds(60),
                Set.of(Subject.household()), title + ".jpg");
        document.markSubmitted("task-" + paperlessId, archivedAt.minusSeconds(30));
        document.markArchived(paperlessId, archivedAt);
        documents.save(document);
        return document;
    }

    private TextRecognitionView view(Document document) {
        return summary(document).textRecognition();
    }

    private DocumentSummary summary(Document document) {
        return presenter.summarizeAll(List.of(documents.findById(document.id()).orElseThrow()), java.util.Map.of())
                .getFirst();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
