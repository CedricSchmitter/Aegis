package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.documents.application.api.UploadDocument.UploadResult;
import dev.aegis.documents.domain.ProcessingState;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentCaptureServiceTest {
    private static final byte[] PDF = "%PDF-1.4 synthetic".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 0x10, 'E', 'x', 'i',
            'f', 0, 0, 'M', 'M', 0, 0x2A, 1, 2, 3};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49,
            0x48, 0x44, 0x52, 0, 0, 4, 0};
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private InMemoryDocumentRepository repository;
    private InMemoryOriginalStore store;
    private TestDoubles.RecordingScheduler scheduler;
    private InMemoryDocumentAnalysisRepository analyses;
    private AnalysisSettings analysisSettings;
    private DocumentCaptureService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        store = new InMemoryOriginalStore();
        scheduler = new TestDoubles.RecordingScheduler();
        ArchivalSettings settings = new ArchivalSettings(3, Duration.ofMinutes(1), Duration.ofDays(1), 1024);
        var classifications = new InMemoryClassificationRepository();
        var categories = new InMemoryCategoryRepository();
        var labels = new InMemoryLabelRepository();
        var lock = new NoOpCatalogLock();
        var presenter = TestDoubles.presenter(scheduler, new ClassificationViews(classifications, categories,
                labels, new InMemoryPlacementSuggestionRepository()));
        analyses = new InMemoryDocumentAnalysisRepository();
        analysisSettings = TestDoubles.analysisSettings();
        service = new DocumentCaptureService(store,
                new DocumentRegistrar(repository, classifications, categories, lock, scheduler, analyses), presenter,
                categories, new TestDoubles.MembersOnly(anna, ben), settings, analysisSettings,
                Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));
    }

    private UploadCommand command(String filename, byte[] bytes) {
        return new UploadCommand(filename, "application/pdf", bytes.length, new ByteArrayInputStream(bytes),
                List.of(new SubjectView("HOUSE", null)));
    }

    @Test
    void acknowledgesOnlyAfterStoringAndSchedulingArchival() {
        UploadResult result = service.upload(new Actor(anna), command("Mietvertrag 2026.pdf", PDF));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.document().title()).isEqualTo("Mietvertrag 2026");
        assertThat(result.document().processing().state()).isEqualTo(ProcessingState.RECEIVED.name());
        assertThat(result.document().processing().retryScheduled()).isTrue();
        assertThat(store.exists(result.document().id() + ".pdf")).isTrue();
        assertThat(scheduler.scheduled).containsExactly(result.document().id());
        assertThat(result.document().checksumSha256()).isEqualTo(InMemoryOriginalStore.sha256(PDF).sha256Hex());
    }

    @Test
    void theSameBytesUploadedAgainAnswerWithTheTrashedDocumentInsteadOfACopy() {
        UploadResult first = service.upload(new Actor(anna), command("scan.pdf", PDF));
        var document = repository.findById(first.document().id()).orElseThrow();
        document.moveToTrash(anna, Instant.parse("2026-09-13T11:00:00Z"),
                dev.aegis.shared.Trash.RecoveryWindow.DEFAULT);
        repository.save(document);

        UploadResult again = service.upload(new Actor(ben), command("scan-again.pdf", PDF));

        assertThat(again.duplicate()).isTrue();
        assertThat(again.document().id()).isEqualTo(first.document().id());
        assertThat(again.document().trashed()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void aRestoreCarriesTheExportedTrashRecordOver() {
        var trash = new dev.aegis.shared.Trash(anna, Instant.parse("2026-09-01T08:00:00Z"),
                Instant.parse("2026-10-01T08:00:00Z"));
        var id = dev.aegis.shared.DocumentId.random();
        service.importDocument(new Actor(anna), new dev.aegis.documents.application.api.ImportDocument
                .ImportDocumentCommand(id, "a", null, "a.pdf", "application/pdf", anna, null, List.of(),
                        InMemoryOriginalStore.sha256(PDF).sha256Hex(), new ByteArrayInputStream(PDF), trash));

        assertThat(repository.findById(id).orElseThrow().trash()).contains(trash);
    }

    @Test
    void sameContentUploadedAgainReturnsExistingRecordWithoutNewWork() {
        UploadResult first = service.upload(new Actor(anna), command("a.pdf", PDF));
        UploadResult second = service.upload(new Actor(ben), command("copy-of-a.pdf", PDF));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(repository.findAll()).hasSize(1);
        assertThat(scheduler.scheduled).hasSize(1);
        assertThat(store.listStagingPaths()).containsExactly(first.document().id() + ".pdf");
    }

    @Test
    void rejectsContentOfAnUnsupportedTypeWhateverItsNameAndRemovesTheStagedFile() {
        assertThatThrownBy(() -> service.upload(new Actor(anna), command("notes.pdf", "hello".getBytes())))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "document.unsupportedType");
        assertThatThrownBy(() -> service.upload(new Actor(anna), command("Foto.jpg", "GIF89a...".getBytes())))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "document.unsupportedType");
        assertThat(store.listStagingPaths()).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aPhotoIsCapturedWithTheTypeOfItsContentNotOfItsName() {
        UploadResult camera = service.upload(new Actor(anna), photo("IMG_20260923_101500.JPG", JPEG));
        UploadResult misnamed = service.upload(new Actor(ben), photo("Brief vom Amt.pdf", PNG));

        assertThat(camera.duplicate()).isFalse();
        assertThat(camera.document().contentType()).isEqualTo("image/jpeg");
        assertThat(camera.document().title()).isEqualTo("IMG 20260923 101500");
        assertThat(camera.document().originalFilename()).isEqualTo("IMG_20260923_101500.JPG");
        assertThat(camera.document().processing().state()).isEqualTo(ProcessingState.RECEIVED.name());
        assertThat(store.exists(camera.document().id() + ".jpg")).isTrue();
        assertThat(misnamed.document().contentType()).isEqualTo("image/png");
        assertThat(misnamed.document().title()).isEqualTo("Brief vom Amt");
        assertThat(scheduler.scheduled).containsExactly(camera.document().id(), misnamed.document().id());
        assertThat(analyses.findById(camera.document().id())).hasValueSatisfying(analysis ->
                assertThat(analysis.awaitsArchive()).isTrue());
    }

    @Test
    void theSamePhotoSharedAgainIsRecognizedByItsChecksum() {
        UploadResult first = service.upload(new Actor(anna), photo("IMG_1.jpg", JPEG));
        UploadResult again = service.upload(new Actor(ben), photo("image.jpg", JPEG));

        assertThat(again.duplicate()).isTrue();
        assertThat(again.document().id()).isEqualTo(first.document().id());
        assertThat(store.listStagingPaths()).containsExactly(first.document().id() + ".jpg");
        assertThat(scheduler.scheduled).hasSize(1);
    }

    @Test
    void aRestoredPhotoKeepsItsImageType() {
        var id = dev.aegis.shared.DocumentId.random();

        var result = service.importDocument(new Actor(anna), new dev.aegis.documents.application.api.ImportDocument
                .ImportDocumentCommand(id, "Brief", "MANUAL", "IMG_1.jpg", "image/jpeg", anna, null, List.of(),
                        InMemoryOriginalStore.sha256(JPEG).sha256Hex(), new ByteArrayInputStream(JPEG), null));

        assertThat(result.outcome()).isEqualTo(dev.aegis.documents.application.api.ImportDocument.ImportOutcome.IMPORTED);
        var restored = repository.findById(id).orElseThrow();
        assertThat(restored.contentType()).isEqualTo("image/jpeg");
        assertThat(restored.stagingPath()).endsWith(".jpg");
    }

    private UploadCommand photo(String filename, byte[] bytes) {
        return new UploadCommand(filename, "application/octet-stream", bytes.length,
                new ByteArrayInputStream(bytes), List.of(new SubjectView("HOUSEHOLD", null)));
    }

    @Test
    void rejectsOversizedAndEmptyFiles() {
        byte[] big = new byte[2048];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        assertThatThrownBy(() -> service.upload(new Actor(anna), command("big.pdf", big)))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.tooLarge");
        assertThatThrownBy(() -> service.upload(new Actor(anna), command("empty.pdf", new byte[0])))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.empty");
        assertThat(store.listStagingPaths()).isEmpty();
    }

    @Test
    void repeatedRestorePreservesThePendingOriginal() throws Exception {
        var first = service.upload(new Actor(anna), command("a.pdf", PDF)).document();
        String path = repository.findById(first.id()).orElseThrow().stagingPath();
        var result = service.importDocument(new Actor(anna), importCommand(first.id(), PDF));
        assertThat(result.outcome()).isEqualTo(dev.aegis.documents.application.api.ImportDocument.ImportOutcome.ALREADY_PRESENT);
        assertThat(store.read(path)).isEqualTo(PDF);
        assertThat(store.listStagingPaths()).containsExactly(path);
        assertThat(scheduler.scheduled).hasSize(1);
    }

    @Test
    void restoreCannotReplaceAnExistingIdWithDifferentBytes() throws Exception {
        var first = service.upload(new Actor(anna), command("a.pdf", PDF)).document();
        byte[] changed = "%PDF-different".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> service.importDocument(new Actor(anna), importCommand(first.id(), changed)))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.importConflict");
        assertThat(store.read(repository.findById(first.id()).orElseThrow().stagingPath())).isEqualTo(PDF);
        assertThat(repository.findById(first.id()).orElseThrow().checksum()).isEqualTo(InMemoryOriginalStore.sha256(PDF));
    }

    @Test
    void restoreRejectsChecksumCollisionUnderAnotherStableId() {
        service.upload(new Actor(anna), command("a.pdf", PDF));
        assertThatThrownBy(() -> service.importDocument(new Actor(anna), importCommand(dev.aegis.shared.DocumentId.random(), PDF)))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.importConflict");
        assertThat(repository.findAll()).hasSize(1);
    }

    private dev.aegis.documents.application.api.ImportDocument.ImportDocumentCommand importCommand(
            dev.aegis.shared.DocumentId id, byte[] bytes) {
        return new dev.aegis.documents.application.api.ImportDocument.ImportDocumentCommand(id, "a", null, "a.pdf",
                "application/pdf", anna, null, List.of(), InMemoryOriginalStore.sha256(bytes).sha256Hex(),
                new ByteArrayInputStream(bytes), null);
    }

    @Test
    void uploadsAreMarkedForAutomaticAnalysisButRestoresNeverAre() {
        UploadResult upload = service.upload(new Actor(anna), command("Scan 0001.pdf", PDF));
        analysisSettings.setAutomatic(false);
        UploadResult manual = service.upload(new Actor(anna), command("b.pdf", "%PDF-1.4 other".getBytes()));
        var restored = service.importDocument(new Actor(anna), importCommand(dev.aegis.shared.DocumentId.random(),
                "%PDF-1.4 restored".getBytes()));

        assertThat(analyses.findById(upload.document().id())).hasValueSatisfying(analysis ->
                assertThat(analysis.awaitsArchive()).isTrue());
        assertThat(upload.document().titleOrigin()).isEqualTo("FILENAME");
        assertThat(analyses.findById(manual.document().id())).isEmpty();
        assertThat(analyses.findById(restored.id())).isEmpty();
        assertThat(repository.findById(restored.id()).orElseThrow().titleOrigin())
                .isEqualTo(dev.aegis.documents.domain.TitleOrigin.MANUAL);
    }

    @Test
    void unknownActorCannotUpload() {
        assertThatThrownBy(() -> service.upload(new Actor(MemberId.random()), command("a.pdf", PDF)))
                .isInstanceOf(NotAuthorizedException.class);
        assertThat(store.listStagingPaths()).isEmpty();
    }

    @Test
    void filenamesAreSanitizedToTheirBaseName() {
        assertThat(DocumentCaptureService.safeFilename("C:\\Users\\x\\..\\Strom Rechnung.PDF"))
                .isEqualTo("Strom Rechnung.PDF");
        assertThat(DocumentCaptureService.safeFilename("../evil:name?.pdf")).isEqualTo("evil_name_.pdf");
        assertThat(DocumentCaptureService.safeFilename("Foto")).as("the content decides the type")
                .isEqualTo("Foto");
        assertThatThrownBy(() -> DocumentCaptureService.safeFilename("a/.."))
                .isInstanceOf(InvalidInputException.class);
    }
}
