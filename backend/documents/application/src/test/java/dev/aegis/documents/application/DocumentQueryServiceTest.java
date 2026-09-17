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
        var views = new ClassificationViews(classifications, categories, labels);
        service = new DocumentQueryService(repository, archive, store, new DocumentPresenter(scheduler, views),
                new InMemoryDocumentCatalog(repository, classifications, categories, labels), categories, labels,
                classifications, views, new TestDoubles.MembersOnly(anna, ben), Clock.fixed(NOW, ZoneOffset.UTC));
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
        assertThat(fromArchive.filename()).isEqualTo("Original.pdf");

        archive.available = false;
        assertThat(service.original(new Actor(ben), document.id()).source()).isEqualTo(OriginalContent.SOURCE_STAGING);

        document.markStagingRemoved();
        repository.save(document);
        assertThatThrownBy(() -> service.original(new Actor(ben), document.id()))
                .isInstanceOf(OriginalUnavailableException.class);
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
