package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.PageEditDoubles.DirectoryCache;
import dev.aegis.documents.application.PageEditDoubles.DirectoryScans;
import dev.aegis.documents.application.PageEditDoubles.FakePdf;
import dev.aegis.documents.application.PageEditDoubles.InMemoryDerivations;
import dev.aegis.documents.application.PageEditDoubles.InMemoryPageEdits;
import dev.aegis.documents.application.api.PageEditCommands.EditRequest;
import dev.aegis.documents.application.api.PageEditCommands.OutputRequest;
import dev.aegis.documents.application.api.PageEditCommands.PageEditView;
import dev.aegis.documents.application.api.PageEditCommands.PageRequest;
import dev.aegis.documents.application.api.PageQueries;
import dev.aegis.documents.application.api.PageQueries.DerivationsView;
import dev.aegis.documents.application.api.PageQueries.SourceRangeView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.ports.OriginalStore.StoredOriginal;
import dev.aegis.documents.application.ports.PdfPages.Placement;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.TitleOrigin;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.pages.Derivation;
import dev.aegis.documents.domain.pages.Derivation.SourceKind;
import dev.aegis.documents.domain.pages.PageEditPlan;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.Trash;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageEditServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private final MemberId lisa = MemberId.random();
    private final MemberId cedric = MemberId.random();
    private final Actor actor = new Actor(lisa);

    @TempDir
    Path cacheDir;
    @TempDir
    Path scanDir;

    private InMemoryDocumentRepository documents;
    private InMemoryOriginalStore store;
    private TestDoubles.FakeArchive archive;
    private TestDoubles.RecordingScheduler scheduler;
    private InMemoryCategoryRepository categories;
    private InMemoryClassificationRepository classifications;
    private InMemoryPageEdits edits;
    private InMemoryDerivations derivations;
    private FakePdf pdf;
    private DirectoryCache cache;
    private DirectoryScans scans;
    private PageEditService service;
    private PageEditRunner runner;
    private PageQueries queries;

    @BeforeEach
    void setUp() {
        documents = new InMemoryDocumentRepository();
        store = new InMemoryOriginalStore();
        archive = new TestDoubles.FakeArchive();
        scheduler = new TestDoubles.RecordingScheduler();
        categories = new InMemoryCategoryRepository();
        classifications = new InMemoryClassificationRepository();
        edits = new InMemoryPageEdits();
        derivations = new InMemoryDerivations();
        pdf = new FakePdf();
        cache = new DirectoryCache(cacheDir);
        scans = new DirectoryScans(scanDir);
        configure(new PageEditPlan.Limits(10, 200, 20, 30), 1_000_000);
    }

    private void configure(PageEditPlan.Limits limits, long maxOutputBytes) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PageEditSettings settings = new PageEditSettings(limits, maxOutputBytes, 3, 480);
        RegisterReceivedDocument registrar = new DocumentRegistrar(documents, classifications, categories,
                new NoOpCatalogLock(), scheduler, new InMemoryDocumentAnalysisRepository());
        PageEditSteps steps = new PageEditStepService(edits, derivations, registrar, scheduler, settings, clock);
        PageSources sources = new PageSources(store, archive, cache, pdf);
        var access = new TestDoubles.MembersOnly(lisa, cedric);
        service = new PageEditService(steps, edits, documents, sources, scans, pdf, categories,
                new TestDoubles.Persons(lisa, cedric), access, settings, clock);
        runner = new PageEditRunner(steps, documents, sources, scans, pdf, store, cache, settings,
                TestDoubles.analysisSettings(), clock);
        queries = new PageQueryService(documents, derivations, sources, cache, pdf, access, settings);
    }

    private Document stored(String title, byte[] content, String contentType) throws IOException {
        DocumentId id = DocumentId.random();
        StoredOriginal original = store.store(id, new ByteArrayInputStream(content));
        Document document = Document.received(id, title, title + ".pdf", contentType, original.sizeBytes(),
                original.checksum(), cedric, NOW, Set.of(Subject.house()), original.stagingPath());
        documents.save(document);
        return document;
    }

    private Document pdfDocument(String name, int pages) throws IOException {
        return stored(name, PageEditDoubles.fakePdf(name, pages), "application/pdf");
    }

    private static PageRequest page(Document document, int index) {
        return new PageRequest(document.id(), index, 0);
    }

    private static OutputRequest output(String title, PageRequest... pages) {
        return new OutputRequest(title, false, null, List.of(new SubjectView("HOUSE", null)), List.of(pages));
    }

    private PageEditView request(UUID id, OutputRequest... outputs) {
        return service.request(actor, new EditRequest(id, List.of(outputs), null, 0));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void splittingAScanCreatesOneDocumentPerPartWithItsOriginAndKeepsTheSource() throws IOException {
        Document scan = pdfDocument("two-letters", 3);
        UUID id = UUID.randomUUID();

        PageEditView requested = request(id, output("Stadtwerke", page(scan, 0), page(scan, 1)),
                output("Versicherung", page(scan, 2)));
        assertThat(requested.state()).isEqualTo("REQUESTED");
        assertThat(requested.results()).isEmpty();
        assertThat(scheduler.pageEdits).containsKey(id);

        assertThat(runner.run(id)).isInstanceOf(PageEditRunner.Done.class);

        PageEditView done = service.get(actor, id);
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.results()).extracting(result -> result.title()).containsExactly("Stadtwerke",
                "Versicherung");
        Document first = documents.findById(done.results().get(0).document()).orElseThrow();
        assertThat(first.titleOrigin()).isEqualTo(TitleOrigin.FILENAME);
        assertThat(first.uploadedBy()).isEqualTo(lisa);
        assertThat(first.contentType()).isEqualTo("application/pdf");
        assertThat(first.subjects()).containsExactly(Subject.house());
        assertThat(scheduler.scheduled).contains(first.id());
        assertThat(derivations.findByDocument(first.id()).orElseThrow().ranges())
                .containsExactly(new Derivation.Range(1, 2, SourceKind.DOCUMENT, scan.id(), 1, 2, 0));
        assertThat(documents.findById(scan.id()).orElseThrow().isTrashed()).isFalse();
        assertThat(pdf.assembled).hasSize(2);
    }

    @Test
    void aRepeatedRequestReturnsTheOperationAndADifferentPlanUnderTheSameIdIsRefused() throws IOException {
        Document scan = pdfDocument("letter", 2);
        UUID id = UUID.randomUUID();
        request(id, output("A", page(scan, 0)));

        PageEditView again = request(id, output("A", page(scan, 0)));

        assertThat(again.id()).isEqualTo(id);
        assertThat(edits.edits).hasSize(1);
        assertCode(() -> request(id, output("A", page(scan, 1))), "pageEdit.conflict");
    }

    @Test
    void anOutputRegisteredBeforeAnInterruptionIsNotCreatedAgain() throws IOException {
        Document local = pdfDocument("local", 1);
        Document archived = pdfDocument("archived", 1);
        archived.markSubmitted("task", NOW);
        archived.markArchived(7, NOW);
        archived.markStagingRemoved();
        documents.save(archived);
        archive.originals.put(7, PageEditDoubles.fakePdf("archived", 1));
        UUID id = UUID.randomUUID();
        request(id, output("First", page(local, 0)), output("Second", page(archived, 0)));
        // The archive goes down after the request was accepted with its sources already cached; a cold cache shows it.
        Files.list(cacheDir).filter(file -> file.getFileName().toString().contains(
                archived.checksum().sha256Hex())).forEach(file -> file.toFile().delete());
        archive.available = false;

        assertThat(runner.run(id)).isInstanceOf(PageEditRunner.Retry.class);
        assertThat(service.get(actor, id).results()).hasSize(1);

        archive.available = true;
        assertThat(runner.run(id)).isInstanceOf(PageEditRunner.Done.class);

        assertThat(service.get(actor, id).results()).hasSize(2);
        assertThat(pdf.assembled).hasSize(2);
        assertThat(documents.findAll()).hasSize(4);
        assertThat(derivations.derivations).hasSize(2);
    }

    @Test
    void theSamePlanInANewOperationReturnsTheDocumentsCreatedBefore() throws IOException {
        Document scan = pdfDocument("letter", 2);
        UUID first = UUID.randomUUID();
        request(first, output("Part", page(scan, 1)));
        runner.run(first);
        UUID second = UUID.randomUUID();
        request(second, output("Part", page(scan, 1)));

        runner.run(second);

        PageEditView view = service.get(actor, second);
        assertThat(view.results()).singleElement().satisfies(result -> {
            assertThat(result.existing()).isTrue();
            assertThat(result.document()).isEqualTo(service.get(actor, first).results().get(0).document());
        });
        assertThat(documents.findAll()).hasSize(2);
    }

    @Test
    void mergingTakesPhotosFromTheArchiveRenditionAndFitsThemToA4() throws IOException {
        Document contract = pdfDocument("contract", 2);
        Document photo = stored("annex photo", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3},
                "image/jpeg");
        UUID notYet = UUID.randomUUID();
        assertCode(() -> request(notYet, output("Contract", page(contract, 0), page(photo, 0))),
                PageSources.NOT_ARCHIVED);

        photo.markSubmitted("task", NOW);
        photo.markArchived(9, NOW);
        documents.save(photo);
        archive.originals.put(9, new byte[] {1});
        archive.archivePdfs.put(9, PageEditDoubles.fakePdf("rendition", 1));
        UUID id = UUID.randomUUID();
        request(id, output("Contract with annex", page(contract, 0), page(contract, 1),
                new PageRequest(photo.id(), 0, 90)));
        runner.run(id);

        assertThat(pdf.assembled.getFirst()).extracting(Placement::fitToA4).containsExactly(false, false, true);
        assertThat(pdf.assembled.getFirst().get(2).rotation()).isEqualTo(90);
        assertThat(service.get(actor, id).state()).isEqualTo("COMPLETED");
    }

    @Test
    void newlyScannedPagesAreStoredDurablyUsedAndRemovedAfterwards() throws IOException {
        Document letter = pdfDocument("letter", 1);
        UUID id = UUID.randomUUID();
        byte[] scan = PageEditDoubles.fakePdf("scan", 2);

        service.request(actor, new EditRequest(id, List.of(output("Letter complete", page(letter, 0),
                new PageRequest(null, 0, 0), new PageRequest(null, 1, 0))), new ByteArrayInputStream(scan),
                scan.length));
        assertThat(scans.files()).isEqualTo(1);

        runner.run(id);

        assertThat(scans.files()).isZero();
        DocumentId result = service.get(actor, id).results().get(0).document();
        assertThat(derivations.findByDocument(result).orElseThrow().ranges()).extracting(Derivation.Range::kind)
                .containsExactly(SourceKind.DOCUMENT, SourceKind.SCAN);
    }

    @Test
    void aScanThatIsNoPdfIsRefusedAndNotKept() throws IOException {
        Document letter = pdfDocument("letter", 1);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};

        assertCode(() -> service.request(actor, new EditRequest(UUID.randomUUID(), List.of(output("A",
                page(letter, 0), new PageRequest(null, 0, 0))), new ByteArrayInputStream(jpeg), jpeg.length)),
                "pageEdit.scan.invalid");
        assertThat(scans.files()).isZero();
        assertThat(edits.edits).isEmpty();
    }

    @Test
    void refusedSourcesAndLimitsAreReportedBeforeAnythingIsStored() throws IOException {
        Document locked = stored("locked", PageEditDoubles.encryptedPdf(), "application/pdf");
        Document trashed = pdfDocument("trashed", 1);
        trashed.moveToTrash(lisa, NOW, new Trash.RecoveryWindow(Duration.ofDays(30)));
        documents.save(trashed);
        Document letter = pdfDocument("letter", 1);
        byte[] scan = PageEditDoubles.fakePdf("scan", 1);

        assertCode(() -> request(UUID.randomUUID(), output("A", page(locked, 0))), "pageEdit.source.encrypted");
        assertCode(() -> request(UUID.randomUUID(), output("A", page(trashed, 0))), "pageEdit.source.trashed");
        assertCode(() -> request(UUID.randomUUID(), output("A", page(letter, 1))), "pageEdit.page.invalid");
        assertCode(() -> request(UUID.randomUUID(), output("A", new PageRequest(null, 0, 0))),
                "pageEdit.scan.missing");
        configure(new PageEditPlan.Limits(1, 200, 20, 30), 1_000_000);
        assertCode(() -> service.request(actor, new EditRequest(UUID.randomUUID(), List.of(output("A",
                page(letter, 0)), output("B", new PageRequest(null, 0, 0))), new ByteArrayInputStream(scan),
                scan.length)), "pageEdit.limit.outputs");
        assertThatThrownBy(() -> request(UUID.randomUUID(), new OutputRequest("A", false,
                UUID.randomUUID(), List.of(), List.of(page(letter, 0))))).isInstanceOf(NotFoundException.class);
        assertThat(scans.files()).isZero();
        assertThat(edits.edits).isEmpty();
    }

    @Test
    void onlyMembersEditPagesAndUnknownOperationsAreNotFound() throws IOException {
        Document letter = pdfDocument("letter", 1);
        Actor stranger = new Actor(MemberId.random());
        assertThatThrownBy(() -> service.request(stranger, new EditRequest(UUID.randomUUID(),
                List.of(output("A", page(letter, 0))), null, 0))).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> queries.pages(stranger, letter.id())).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.get(actor, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anOutputAboveTheUploadLimitFailsTheOperationWithItsCode() throws IOException {
        Document letter = pdfDocument("letter", 1);
        configure(new PageEditPlan.Limits(10, 200, 20, 30), 20);
        UUID id = UUID.randomUUID();
        request(id, output("Too big", page(letter, 0)));

        assertThat(runner.run(id)).isInstanceOf(PageEditRunner.Done.class);

        PageEditView view = service.get(actor, id);
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo("document.tooLarge");
        assertThat(documents.findAll()).hasSize(1);
    }

    @Test
    void theFolderAndATitleTheMemberTypedAreKept() throws IOException {
        Document letter = pdfDocument("letter", 1);
        var labels = new InMemoryLabelRepository();
        UUID folder = new CategoryService(categories, labels, classifications, documents,
                new InMemoryDocumentCatalog(documents, classifications, categories, labels), new NoOpCatalogLock(),
                new TestDoubles.Persons(lisa, cedric), new TestDoubles.MembersOnly(lisa, cedric),
                Clock.fixed(NOW, ZoneOffset.UTC)).create(actor, new CreateCategory(null, null, "Versicherung")).id();
        UUID id = UUID.randomUUID();
        request(id, new OutputRequest("Police 2026", true, folder,
                List.of(new SubjectView("MEMBER", lisa)), List.of(page(letter, 0))));

        runner.run(id);

        Document result = documents.findById(service.get(actor, id).results().get(0).document()).orElseThrow();
        assertThat(result.titleOrigin()).isEqualTo(TitleOrigin.MANUAL);
        assertThat(result.subjects()).containsExactly(Subject.member(lisa));
        assertThat(classifications.findById(result.id()).orElseThrow().categoryId()).contains(new CategoryId(folder));
    }

    @Test
    void pagesPreviewsAndOriginsAreShownForMembers() throws IOException {
        Document scan = stored("signed letter", "%PDF-fake pages=2 signed attachment".getBytes(), "application/pdf");
        PageQueries.PagesView pages = queries.pages(actor, scan.id());
        assertThat(pages.pages()).hasSize(2);
        assertThat(pages.warnings()).containsExactly(PageQueries.WARNING_SIGNED,
                PageQueries.WARNING_EMBEDDED_FILES);
        assertThat(pages.refusal()).isNull();

        byte[] preview = queries.preview(actor, scan.id(), 1);
        assertThat(queries.preview(actor, scan.id(), 1)).isEqualTo(preview);
        assertThat(pdf.renders).isEqualTo(1);
        assertThatThrownBy(() -> queries.preview(actor, scan.id(), 2)).isInstanceOf(NotFoundException.class);

        UUID id = UUID.randomUUID();
        request(id, output("Part", page(scan, 1)));
        runner.run(id);
        DocumentId part = service.get(actor, id).results().get(0).document();

        DerivationsView origin = queries.derivations(actor, part);
        assertThat(origin.origin().ranges()).singleElement().satisfies(range -> {
            assertThat(range.source()).isEqualTo(scan.id());
            assertThat(range.sourceTitle()).isEqualTo("signed letter");
            assertThat(range.sourceState()).isEqualTo(SourceRangeView.AVAILABLE);
            assertThat(range.firstSourcePage()).isEqualTo(2);
        });
        assertThat(queries.derivations(actor, scan.id()).derived()).singleElement()
                .satisfies(derived -> assertThat(derived.sourcePages()).containsExactly(new PageQueries.PageSpan(2,
                        2)));

        Document source = documents.findById(scan.id()).orElseThrow();
        source.moveToTrash(lisa, NOW, new Trash.RecoveryWindow(Duration.ofDays(30)));
        documents.save(source);
        assertThat(queries.derivations(actor, part).origin().ranges().get(0).sourceState())
                .isEqualTo(SourceRangeView.TRASHED);
        assertThat(queries.pages(actor, scan.id()).refusal()).isEqualTo("pageEdit.source.trashed");
        derivations.sourceDeleted(scan.id());
        assertThat(queries.derivations(actor, part).origin().ranges().get(0).sourceState())
                .isEqualTo(SourceRangeView.DELETED);
    }

    @Test
    void anEncryptedDocumentListsNoPagesButSaysWhy() throws IOException {
        Document locked = stored("locked", PageEditDoubles.encryptedPdf(), "application/pdf");
        PageQueries.PagesView pages = queries.pages(actor, locked.id());
        assertThat(pages.pages()).isEmpty();
        assertThat(pages.refusal()).isEqualTo("pageEdit.source.encrypted");
    }
}
