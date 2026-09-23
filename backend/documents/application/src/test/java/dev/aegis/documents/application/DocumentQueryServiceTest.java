package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.OriginalUnavailableException;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private InMemoryDocumentRepository repository;
    private InMemoryOriginalStore store;
    private TestDoubles.FakeArchive archive;
    private DocumentQueryService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        store = new InMemoryOriginalStore();
        archive = new TestDoubles.FakeArchive();
        var scheduler = new TestDoubles.RecordingScheduler();
        var classifications = new InMemoryClassificationRepository();
        var categories = new InMemoryCategoryRepository();
        var labels = new InMemoryLabelRepository();
        var placements = new InMemoryPlacementSuggestionRepository();
        var views = new ClassificationViews(classifications, categories, labels, placements);
        service = new DocumentQueryService(repository, archive, store, TestDoubles.presenter(scheduler, views),
                new InMemoryDocumentCatalog(repository, classifications, categories, labels), categories, labels,
                classifications, views, placements, new InMemoryDocumentAnalysisRepository(),
                TestDoubles.analysisSettings(),
                new TestDoubles.MembersOnly(anna, ben), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Document stored(String title, MemberId uploader, Instant at, byte[] bytes) throws Exception {
        DocumentId id = DocumentId.random();
        var stored = store.store(id, new ByteArrayInputStream(bytes));
        Document document = Document.received(id, title, title + ".pdf", "application/pdf", bytes.length,
                stored.checksum(), uploader, at, Set.of(Subject.member(uploader)), stored.stagingPath());
        repository.save(document);
        return document;
    }

    @Test
    void bothMembersSeeTheWholeCollectionRegardlessOfSubjectOrUploader() throws Exception {
        stored("Annas Vertrag", anna, NOW, "%PDF-a".getBytes(StandardCharsets.US_ASCII));
        stored("Bens Rechnung", ben, NOW.plusSeconds(1), "%PDF-b".getBytes(StandardCharsets.US_ASCII));

        DocumentSearchResult forBen = service.search(new Actor(ben), "");

        assertThat(forBen.items()).extracting("title").containsExactly("Bens Rechnung", "Annas Vertrag");
        assertThat(forBen.contentSearch()).isEqualTo(ContentSearchState.NOT_APPLIED);
        assertThat(service.search(new Actor(anna), "").items()).hasSize(2);
    }

    @Test
    void searchMergesTitleAndContentHitsAndReportsUnavailableArchive() throws Exception {
        Document titleHit = stored("Stromvertrag", anna, NOW, "%PDF-1".getBytes(StandardCharsets.US_ASCII));
        Document contentHit = stored("Scan 0815", anna, NOW.plusSeconds(5), "%PDF-2".getBytes(StandardCharsets.US_ASCII));
        contentHit.markArchived(99, NOW);
        repository.save(contentHit);
        archive.contentHits.put("strom", List.of(99));

        DocumentSearchResult ready = service.search(new Actor(anna), " strom ");
        assertThat(ready.contentSearch()).isEqualTo(ContentSearchState.READY);
        assertThat(ready.items()).extracting("id").containsExactly(contentHit.id(), titleHit.id());

        archive.available = false;
        DocumentSearchResult degraded = service.search(new Actor(anna), "strom");
        assertThat(degraded.contentSearch()).isEqualTo(ContentSearchState.UNAVAILABLE);
        assertThat(degraded.items()).extracting("id").containsExactly(titleHit.id());
    }

    @Test
    void originalComesFromArchiveWhenArchivedAndFallsBackToStaging() throws Exception {
        byte[] bytes = "%PDF-original".getBytes(StandardCharsets.US_ASCII);
        Document document = stored("Original", anna, NOW, bytes);
        assertThat(service.original(new Actor(ben), document.id()).source()).isEqualTo(OriginalContent.SOURCE_STAGING);

        document.markArchived(5, NOW);
        repository.save(document);
        archive.originals.put(5, bytes);
        OriginalContent fromArchive = service.original(new Actor(ben), document.id());
        assertThat(fromArchive.source()).isEqualTo(OriginalContent.SOURCE_ARCHIVE);
        assertThat(fromArchive.bytes()).isEqualTo(bytes);
        // Without analysis values the friendly name falls back to the title plus the stable id; bytes stay unchanged.
        assertThat(fromArchive.filename()).isEqualTo("Original_" + document.id().toString().substring(0, 8) + ".pdf");
        assertThat(document.checksum().matches(fromArchive.bytes())).isTrue();

        archive.available = false;
        assertThat(service.original(new Actor(ben), document.id()).source()).isEqualTo(OriginalContent.SOURCE_STAGING);

        document.markStagingRemoved();
        repository.save(document);
        assertThatThrownBy(() -> service.original(new Actor(ben), document.id()))
                .isInstanceOf(OriginalUnavailableException.class);
    }

    @Test
    void aPhotoIsServedAsTheOriginalItIsAndItsArchivePdfSeparately() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        DocumentId id = DocumentId.random();
        var staged = store.store(id, new ByteArrayInputStream(jpeg));
        Document photo = Document.received(id, "Brief vom Amt", "IMG_1.jpg", "image/jpeg", jpeg.length,
                staged.checksum(), anna, NOW, Set.of(Subject.household()), staged.stagingPath());
        repository.save(photo);
        String stem = "Brief-vom-Amt_" + id.toString().substring(0, 8);

        OriginalContent beforeArchival = service.original(new Actor(ben), id);
        assertThat(beforeArchival.contentType()).isEqualTo("image/jpeg");
        assertThat(beforeArchival.filename()).isEqualTo(stem + ".jpg");
        assertThatThrownBy(() -> service.archivePdf(new Actor(ben), id)).as("not archived yet")
                .isInstanceOf(NotFoundException.class).hasFieldOrPropertyWithValue("resource", "archivePdf");

        photo.markArchived(7, NOW);
        repository.save(photo);
        archive.originals.put(7, jpeg);
        byte[] searchable = "%PDF-1.7 searchable".getBytes(StandardCharsets.US_ASCII);
        archive.archivePdfs.put(7, searchable);

        assertThat(service.original(new Actor(ben), id).bytes()).isEqualTo(jpeg);
        var pdf = service.archivePdf(new Actor(ben), id);
        assertThat(pdf.bytes()).isEqualTo(searchable);
        assertThat(pdf.filename()).isEqualTo(stem + ".pdf");
        assertThatThrownBy(() -> service.archivePdf(new Actor(MemberId.random()), id))
                .isInstanceOf(dev.aegis.shared.NotAuthorizedException.class);

        archive.archivePdfs.remove(7);
        assertThatThrownBy(() -> service.archivePdf(new Actor(ben), id)).as("the archive keeps no archive version")
                .isInstanceOf(NotFoundException.class);
        archive.available = false;
        assertThatThrownBy(() -> service.archivePdf(new Actor(ben), id))
                .isInstanceOf(dev.aegis.documents.application.ports.ArchiveUnavailableException.class);
    }

    @Test
    void corruptedArchiveFallsBackAndCleanupKeepsTheOnlyValidOriginal() throws Exception {
        byte[] bytes = "%PDF-original".getBytes(StandardCharsets.US_ASCII);
        Document document = stored("Original", anna, NOW.minusSeconds(900000), bytes);
        document.markArchived(5, NOW.minusSeconds(800000));
        archive.originals.put(5, "%PDF-corrupted".getBytes(StandardCharsets.US_ASCII));
        assertThat(service.original(new Actor(ben), document.id()).bytes()).isEqualTo(bytes);
        var settings = new ArchivalSettings(3, java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(7), 1024);
        var reconciliation = new ReconciliationService(repository, store, archive,
                new TestDoubles.RecordingScheduler(), settings, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(reconciliation.reconcile().stagingRemoved()).isZero();
        assertThat(store.exists(document.stagingPath())).isTrue();
        archive.originals.put(5, bytes);
        assertThat(reconciliation.reconcile().stagingRemoved()).isEqualTo(1);
        assertThat(store.exists(document.stagingPath())).isFalse();
        archive.originals.put(5, "%PDF-corrupted".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> service.original(new Actor(ben), document.id()))
                .isInstanceOf(OriginalUnavailableException.class);
    }

    @Test
    void statusReportsArchiveProbeAndCounts() throws Exception {
        Document document = stored("A", anna, NOW, "%PDF-a".getBytes(StandardCharsets.US_ASCII));
        document.markArchived(1, NOW.minusSeconds(60));
        repository.save(document);
        stored("B", anna, NOW, "%PDF-b".getBytes(StandardCharsets.US_ASCII));

        assertThat(service.processingCounts(new Actor(anna))).containsEntry("ARCHIVED", 1L).containsEntry("RECEIVED",
                1L);
        var health = service.archiveHealth(new Actor(anna));
        assertThat(health.reachable()).isTrue();
        assertThat(health.lastSuccessfulArchivalAt()).isEqualTo(NOW.minusSeconds(60));

        archive.available = false;
        assertThat(service.archiveHealth(new Actor(anna)).reachable()).isFalse();
    }
}
