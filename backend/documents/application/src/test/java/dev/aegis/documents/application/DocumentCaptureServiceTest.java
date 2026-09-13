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
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private InMemoryDocumentRepository repository;
    private InMemoryOriginalStore store;
    private TestDoubles.RecordingScheduler scheduler;
    private DocumentCaptureService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        store = new InMemoryOriginalStore();
        scheduler = new TestDoubles.RecordingScheduler();
        ArchivalSettings settings = new ArchivalSettings(3, Duration.ofMinutes(1), Duration.ofDays(1), 1024);
        service = new DocumentCaptureService(store, new DocumentRegistrar(repository, scheduler),
                new DocumentPresenter(scheduler), new TestDoubles.MembersOnly(anna, ben), settings,
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
    void rejectsNonPdfContentAndRemovesTheStagedFile() {
        assertThatThrownBy(() -> service.upload(new Actor(anna), command("notes.pdf", "hello".getBytes())))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.notPdf");
        assertThatThrownBy(() -> service.upload(new Actor(anna), command("image.png", PDF)))
                .isInstanceOf(InvalidInputException.class).hasFieldOrPropertyWithValue("code", "document.notPdf");
        assertThat(store.listStagingPaths()).isEmpty();
        assertThat(repository.findAll()).isEmpty();
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
        return new dev.aegis.documents.application.api.ImportDocument.ImportDocumentCommand(id, "a", "a.pdf",
                "application/pdf", anna, null, List.of(), InMemoryOriginalStore.sha256(bytes).sha256Hex(),
                new ByteArrayInputStream(bytes));
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
        assertThatThrownBy(() -> DocumentCaptureService.safeFilename("report.docx"))
                .isInstanceOf(InvalidInputException.class);
    }
}
