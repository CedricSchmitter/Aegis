package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.AutomaticClassification.Assessment;
import dev.aegis.documents.application.api.AutomaticClassification.LabelClaim;
import dev.aegis.documents.application.api.AutomaticClassification.PlacementClaim;
import dev.aegis.documents.application.api.AutomaticClassification.Proposal;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.DocumentSummary;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.PlacementCommands.AcceptPlacement;
import dev.aegis.documents.application.api.PlacementCommands.AcceptedPlacement;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.StaleVersionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Folder placement by the classifier (issue #67, ADR-0005) against in-memory repositories and a controlled AI
 * boundary: the automatic move into an existing folder, proposals for paths that do not exist yet, the deterministic
 * reuse of folders, and the member decisions that every later run has to respect.
 */
class PlacementFlowTest {
    private static final String INSURANCE_TEXT = """
            Muster Versicherung AG
            Hausratversicherung - Versicherungsschein vom 02.03.2026
            Versicherungsort: Musterweg 1 (unser Haus)
            Jahresbeitrag 148,00 EUR""";
    private static final String CAR_TEXT = """
            Muster Versicherung AG
            Kfz-Versicherung - Versicherungsschein vom 02.03.2026
            Fahrzeug: Musterwagen, amtliches Kennzeichen XX-YY 123""";
    private static final AtomicInteger PAPERLESS_IDS = new AtomicInteger(500);

    private CatalogFixture f;
    private InMemoryDocumentAnalysisRepository analyses;
    private TestDoubles.FakeArchive archive;
    private TestDoubles.RecordingRuns runs;
    private DocumentAnalysisService service;
    private LabelView insurance;
    private LabelView policy;
    private CategoryView haus;
    private CategoryView versicherungen;

    @BeforeEach
    void setUp() {
        f = new CatalogFixture();
        analyses = new InMemoryDocumentAnalysisRepository();
        archive = new TestDoubles.FakeArchive();
        runs = new TestDoubles.RecordingRuns();
        AnalysisSettings settings = TestDoubles.analysisSettings();
        DocumentPresenter presenter = new DocumentPresenter(f.scheduler, f.views, new AnalysisViews(analyses, runs,
                settings), new InMemoryArchiveTextCheckRepository());
        service = new DocumentAnalysisService(f.documents, analyses, f.classifications, f.labels, f.categories,
                f.catalog, f.classificationService, f.labelService, f.lock, archive, runs, f.views, presenter,
                f.persons, f.access, settings, f.clock);
        insurance = f.label("TOPIC", "Versicherung");
        policy = f.label("DOCUMENT_TYPE", "Versicherungspolice");
        haus = f.folder("Haus", null);
        versicherungen = f.folder("Versicherungen", haus.id());
        // The folder passes the topic on, so a document carrying it belongs there by its labels, not by its name.
        versicherungen = f.categoryService.changeImplications(f.actor, versicherungen.id(),
                List.of(insurance.id()), List.of(new SubjectView("HOUSE", null)), versicherungen.version());
    }

    // Helpers ---------------------------------------------------------------------------------------------------

    private Document archived(String title, String text) {
        Document document = f.document(title);
        int paperlessId = PAPERLESS_IDS.incrementAndGet();
        document.markArchived(paperlessId, CatalogFixture.NOW);
        f.documents.save(document);
        archive.texts.put(paperlessId, text);
        return document;
    }

    private int request(Document document) {
        long version = analyses.findById(document.id()).map(DocumentAnalysis::version).orElse(0L);
        service.requestAnalysis(f.actor, document.id(), version);
        return analyses.findById(document.id()).orElseThrow().generation();
    }

    /** A confident insurance-policy result with the placement the model proposes. */
    private Proposal proposal(int generation, long classificationVersion, PlacementClaim placement,
            List<String> flags) {
        return new Proposal(generation, classificationVersion, null, "Hausratversicherung Versicherungsschein",
                "2026-03-02", "Versicherungsschein vom 02.03.2026", false,
                new LabelClaim(policy.id(), null, 0.95, "Versicherungsschein"),
                new LabelClaim(null, "Muster Versicherung AG", 0.9, "Muster Versicherung AG"),
                List.of(new LabelClaim(insurance.id(), null, 0.92, "Hausratversicherung")), "Hausrat", List.of(),
                placement, flags);
    }

    private Assessment run(Document document, PlacementClaim placement, List<String> flags) {
        int generation = request(document);
        Proposal proposal = proposal(generation, f.classificationVersion(document.id()), placement, flags);
        Assessment assessment = service.assess(f.actor, document.id(), proposal);
        service.apply(f.actor, assessment, "ai-run:test", "claude-sonnet-5");
        return assessment;
    }

    private ClassificationView classification(DocumentId id) {
        return f.views.viewOf(id);
    }

    private static PlacementClaim folder(UUID folderId, double confidence, String evidence) {
        return new PlacementClaim(folderId, null, List.of(), confidence, evidence);
    }

    private static PlacementClaim path(UUID anchor, List<String> segments, double confidence, String evidence) {
        return new PlacementClaim(null, anchor, segments, confidence, evidence);
    }

    // Automatic move --------------------------------------------------------------------------------------------

    @Test
    void anExistingFolderWhoseLabelsMatchTakesTheDocumentAutomatically() {
        Document document = archived("Scan 0001", INSURANCE_TEXT);

        run(document, folder(versicherungen.id(), 0.93, "Hausratversicherung"), List.of());

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isEqualTo(versicherungen.id());
        assertThat(view.path()).extracting(ref -> ref.name()).containsExactly("Haus", "Versicherungen");
        assertThat(view.placement().origin()).isEqualTo("AUTOMATIC");
        assertThat(view.placement().confidence()).isEqualTo(0.93);
        assertThat(view.placement().sourceRef()).isEqualTo("ai-run:test");
        assertThat(view.placement().revertible()).isTrue();
        assertThat(view.placementSuggestion()).isNull();
    }

    @Test
    void aFolderWithoutMatchingLabelsOrWithoutEvidenceOnlyBecomesASuggestion() {
        CategoryView vertraege = f.folder("Verträge", haus.id());
        Document document = archived("Scan 0002", INSURANCE_TEXT);

        // The folder implies nothing, so its name alone cannot justify a move (ADR-0002).
        run(document, folder(vertraege.id(), 0.99, "Versicherungsschein"), List.of());

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isNull();
        assertThat(view.placementSuggestion()).isNotNull();
        assertThat(view.placementSuggestion().path()).containsExactly("Haus", "Verträge");
        assertThat(view.placementSuggestion().newFolders()).isEmpty();
    }

    @Test
    void aQuoteThatIsNotInTheTextNeverMovesTheDocument() {
        Document document = archived("Scan 0003", INSURANCE_TEXT);

        run(document, folder(versicherungen.id(), 0.99, "Kfz-Versicherung Vollkasko"), List.of());

        assertThat(classification(document.id()).categoryId()).isNull();
        assertThat(classification(document.id()).placementSuggestion()).isNotNull();
    }

    @Test
    void embeddedInstructionsNeverMoveADocumentOrCreateFolders() {
        Document document = archived("Scan 0004", INSURANCE_TEXT);

        run(document, path(haus.id(), List.of("Geheim"), 0.99, "Hausratversicherung"),
                List.of("EMBEDDED_INSTRUCTIONS"));

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isNull();
        assertThat(f.categories.load().all()).extracting(category -> category.name())
                .doesNotContain("Geheim");
        assertThat(view.placementSuggestion().newFolders()).containsExactly("Geheim");
    }

    @Test
    void withoutAiTheDocumentStaysUnsortedAndFullyUsable() {
        runs.refusal = "ai.notConfigured";
        Document document = archived("Scan 0005", INSURANCE_TEXT);

        request(document);

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isNull();
        assertThat(view.placement().origin()).isNull();
        assertThat(view.placementSuggestion()).isNull();
        // Filing it by hand still works while the model is unavailable.
        DocumentSummary placed = f.classificationService.place(f.actor, document.id(), versicherungen.id(),
                view.version());
        assertThat(placed.classification().categoryId()).isEqualTo(versicherungen.id());
    }

    // Proposed paths --------------------------------------------------------------------------------------------

    @Test
    void acceptingAProposedPathCreatesTheMissingFoldersAndMovesTheDocument() {
        Document document = archived("Scan 0006", INSURANCE_TEXT);
        run(document, path(versicherungen.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"), List.of());

        ClassificationView before = classification(document.id());
        assertThat(before.categoryId()).isNull();
        assertThat(before.placementSuggestion().path()).containsExactly("Haus", "Versicherungen", "Hausrat");
        assertThat(before.placementSuggestion().newFolders()).containsExactly("Hausrat");
        // The effect of the target is visible before accepting: the anchor passes the house subject on, while the
        // topic it also implies is already an own label of the document and therefore not listed again.
        assertThat(before.placementSuggestion().impliedSubjects()).extracting(SubjectView::kind)
                .containsExactly("HOUSE");
        assertThat(before.placementSuggestion().impliedLabels()).isEmpty();

        AcceptedPlacement accepted = f.placementService.acceptPlacement(f.actor, document.id(),
                new AcceptPlacement(null, versicherungen.id(), List.of("Hausrat"), before.version(),
                        before.placementSuggestion().anchorVersion(), false));

        assertThat(accepted.createdFolders()).isEqualTo(1);
        assertThat(accepted.movedDocuments()).isEqualTo(1);
        assertThat(accepted.path()).containsExactly("Haus", "Versicherungen", "Hausrat");
        ClassificationView after = classification(document.id());
        assertThat(after.categoryId()).isEqualTo(accepted.folderId());
        assertThat(after.placement().origin()).isEqualTo("MANUAL");
        assertThat(after.placementSuggestion()).isNull();
    }

    @Test
    void aMemberMayEditTheProposedNamesBeforeAccepting() {
        Document document = archived("Scan 0007", INSURANCE_TEXT);
        run(document, path(versicherungen.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"), List.of());
        ClassificationView before = classification(document.id());

        AcceptedPlacement accepted = f.placementService.acceptPlacement(f.actor, document.id(),
                new AcceptPlacement(null, haus.id(), List.of("Hausratversicherung 2026"), before.version(),
                        null, false));

        assertThat(accepted.path()).containsExactly("Haus", "Hausratversicherung 2026");
        assertThat(classification(document.id()).categoryId()).isEqualTo(accepted.folderId());
    }

    @Test
    void aPathThatMeanwhileExistsResolvesToThatFolderInsteadOfANearDuplicate() {
        Document document = archived("Scan 0008", INSURANCE_TEXT);
        int generation = request(document);
        Proposal proposal = proposal(generation, f.classificationVersion(document.id()),
                path(versicherungen.id(), List.of("hausrat"), 0.9, "Hausratversicherung"), List.of());
        Assessment assessment = service.assess(f.actor, document.id(), proposal);
        // While the run was working, a member created the folder with a different spelling.
        CategoryView created = f.categoryService.create(f.actor, new CreateCategory(null, versicherungen.id(),
                "Hausrat"));

        service.apply(f.actor, assessment, "ai-run:test", "claude-sonnet-5");

        // The folder that now exists is reused; no second folder differing only in case is created.
        assertThat(f.categories.load().childrenOf(new CategoryId(versicherungen.id())))
                .extracting(Category::name).containsExactly("Hausrat");
        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isEqualTo(created.id());
        assertThat(view.placementSuggestion()).isNull();
    }

    @Test
    void aFolderDeletedDuringARunFallsBackToTheProposedPath() {
        CategoryView temporary = f.folder("Vorläufig", haus.id());
        Document document = archived("Scan 0009", INSURANCE_TEXT);
        int generation = request(document);
        Proposal proposal = proposal(generation, f.classificationVersion(document.id()),
                new PlacementClaim(temporary.id(), haus.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"),
                List.of());
        Assessment assessment = service.assess(f.actor, document.id(), proposal);
        f.categoryService.delete(f.actor, temporary.id(), f.categoryVersion(temporary.id()));

        service.apply(f.actor, assessment, "ai-run:test", "claude-sonnet-5");

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isNull();
        assertThat(view.placementSuggestion().path()).containsExactly("Haus", "Hausrat");
    }

    @Test
    void aFolderRenamedDuringARunKeepsItsIdentity() {
        Document document = archived("Scan 0010", INSURANCE_TEXT);
        int generation = request(document);
        Proposal proposal = proposal(generation, f.classificationVersion(document.id()),
                folder(versicherungen.id(), 0.93, "Hausratversicherung"), List.of());
        Assessment assessment = service.assess(f.actor, document.id(), proposal);
        f.categoryService.rename(f.actor, versicherungen.id(), "Policen", f.categoryVersion(versicherungen.id()));

        service.apply(f.actor, assessment, "ai-run:test", "claude-sonnet-5");

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isEqualTo(versicherungen.id());
        assertThat(view.path()).extracting(ref -> ref.name()).containsExactly("Haus", "Policen");
    }

    @Test
    void acceptingAnEditedPathReportsAConcurrentTreeChange() {
        Document document = archived("Scan 0011", INSURANCE_TEXT);
        run(document, path(versicherungen.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"), List.of());
        ClassificationView before = classification(document.id());
        long anchorVersion = before.placementSuggestion().anchorVersion();
        f.categoryService.rename(f.actor, versicherungen.id(), "Policen", f.categoryVersion(versicherungen.id()));

        assertThatThrownBy(() -> f.placementService.acceptPlacement(f.actor, document.id(),
                new AcceptPlacement(null, versicherungen.id(), List.of("Hausrat"), before.version(), anchorVersion,
                        false)))
                .isInstanceOf(StaleVersionException.class);
        assertThat(classification(document.id()).categoryId()).isNull();
    }

    // Member decisions ------------------------------------------------------------------------------------------

    @Test
    void aDocumentAMemberFiledIsNeitherMovedNorProposedAbout() {
        Document document = archived("Scan 0012", INSURANCE_TEXT);
        f.classificationService.place(f.actor, document.id(), haus.id(), f.classificationVersion(document.id()));

        run(document, folder(versicherungen.id(), 0.99, "Hausratversicherung"), List.of());

        ClassificationView view = classification(document.id());
        assertThat(view.categoryId()).isEqualTo(haus.id());
        assertThat(view.placement().origin()).isEqualTo("MANUAL");
        assertThat(view.placementSuggestion()).isNull();
    }

    @Test
    void aRejectedTargetIsNotProposedAgain() {
        Document document = archived("Scan 0013", INSURANCE_TEXT);
        run(document, path(versicherungen.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"), List.of());
        ClassificationView proposed = classification(document.id());

        f.placementService.rejectPlacement(f.actor, document.id(), proposed.version());
        assertThat(classification(document.id()).placementSuggestion()).isNull();

        run(document, path(versicherungen.id(), List.of("Hausrat"), 0.95, "Hausratversicherung"), List.of());

        assertThat(classification(document.id()).placementSuggestion()).isNull();
        assertThat(classification(document.id()).categoryId()).isNull();
    }

    @Test
    void revertingAnAutomaticMoveReturnsTheDocumentAndBlocksThatFolderLater() {
        Document document = archived("Scan 0014", INSURANCE_TEXT);
        run(document, folder(versicherungen.id(), 0.93, "Hausratversicherung"), List.of());
        ClassificationView moved = classification(document.id());

        f.placementService.revertPlacement(f.actor, document.id(), moved.version());

        ClassificationView reverted = classification(document.id());
        assertThat(reverted.categoryId()).isNull();
        assertThat(reverted.placement().byMember()).isTrue();
        assertThat(reverted.placement().revertible()).isFalse();

        run(document, folder(versicherungen.id(), 0.99, "Hausratversicherung"), List.of());

        assertThat(classification(document.id()).categoryId()).isNull();
        assertThat(classification(document.id()).placementSuggestion()).isNull();
    }

    @Test
    void revertingIsRefusedForAPlacementTheMemberMadeThemselves() {
        Document document = archived("Scan 0015", INSURANCE_TEXT);
        f.classificationService.place(f.actor, document.id(), versicherungen.id(),
                f.classificationVersion(document.id()));

        assertThatThrownBy(() -> f.placementService.revertPlacement(f.actor, document.id(),
                f.classificationVersion(document.id())))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "placement.notAutomatic");
    }

    // Grouping --------------------------------------------------------------------------------------------------

    @Test
    void oneAcceptanceCanServeEveryDocumentWithTheSameProposedPath() {
        Document first = archived("Scan 0016", INSURANCE_TEXT);
        Document second = archived("Scan 0017", CAR_TEXT);
        run(first, path(versicherungen.id(), List.of("Hausrat"), 0.9, "Hausratversicherung"), List.of());
        run(second, path(versicherungen.id(), List.of("hausrat"), 0.9, "Versicherungsschein"), List.of());

        ClassificationView view = classification(first.id());
        assertThat(view.placementSuggestion().otherDocuments()).isEqualTo(1);

        AcceptedPlacement accepted = f.placementService.acceptPlacement(f.actor, first.id(),
                new AcceptPlacement(null, versicherungen.id(), List.of("Hausrat"), view.version(),
                        view.placementSuggestion().anchorVersion(), true));

        assertThat(accepted.createdFolders()).isEqualTo(1);
        assertThat(accepted.movedDocuments()).isEqualTo(2);
        assertThat(classification(second.id()).categoryId()).isEqualTo(accepted.folderId());
        assertThat(classification(second.id()).placementSuggestion()).isNull();
    }

    @Test
    void acceptingNeedsAPendingSuggestion() {
        Document document = archived("Scan 0018", INSURANCE_TEXT);

        assertThatThrownBy(() -> f.placementService.acceptPlacement(f.actor, document.id(),
                new AcceptPlacement(versicherungen.id(), null, List.of(),
                        f.classificationVersion(document.id()), null, false)))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "placement.suggestion.missing");
    }

    // Catalog context -------------------------------------------------------------------------------------------

    @Test
    void theClassifierInputCarriesTheFolderTreeInADeterministicOrder() {
        f.folder("Auto", null);
        CategoryView vertraege = f.folder("Verträge", haus.id());
        Document document = archived("Scan 0019", INSURANCE_TEXT);
        int generation = request(document);

        var input = service.input(f.actor, document.id(), generation);

        assertThat(input.folders().omitted()).isZero();
        assertThat(input.folders().folders()).extracting(folder -> String.join(" / ", folder.path()))
                .containsExactly("Auto", "Haus", "Haus / Versicherungen", "Haus / Verträge");
        assertThat(input.folders().folders()).filteredOn(folder -> folder.id().equals(versicherungen.id()))
                .singleElement().satisfies(folder -> {
                    assertThat(folder.impliedLabels()).containsExactly("Versicherung");
                    assertThat(folder.impliedSubjects()).containsExactly("HOUSE");
                    assertThat(folder.documentCount()).isZero();
                });
        assertThat(input.currentFolderPath()).isEmpty();
        assertThat(vertraege.id()).isNotNull();
        // The same tree always produces the same list, so the prompt prefix stays stable and cacheable.
        assertThat(service.input(f.actor, document.id(), generation).folders()).isEqualTo(input.folders());
    }

    @Test
    void aTreeBeyondTheBoundIsReducedAndSaysHowManyFoldersAreMissing() {
        for (int i = 0; i < 12; i++) {
            f.folder("Leerer Ordner " + i, haus.id());
        }
        Document document = archived("Scan 0020", INSURANCE_TEXT);
        f.classificationService.place(f.actor, document.id(), versicherungen.id(),
                f.classificationVersion(document.id()));
        int generation = request(document);
        DocumentAnalysisService bounded = boundedTo(5);

        var input = bounded.input(f.actor, document.id(), generation);

        assertThat(input.folders().folders()).hasSizeLessThanOrEqualTo(5);
        assertThat(input.folders().omitted()).isPositive();
        // Folders holding documents survive the reduction; empty ones are dropped first.
        assertThat(input.folders().folders()).extracting(folder -> String.join(" / ", folder.path()))
                .contains("Haus", "Haus / Versicherungen");
        assertThat(input.currentFolderPath()).containsExactly("Haus", "Versicherungen");
    }

    /** The same service with a smaller catalog bound, to exercise the documented reduction strategy. */
    private DocumentAnalysisService boundedTo(int maxFolders) {
        AnalysisSettings settings = new AnalysisSettings(true,
                dev.aegis.documents.domain.naming.BuiltinLabelNames.Language.DE, 5_000, maxFolders,
                java.time.ZoneId.of("Europe/Berlin"));
        return new DocumentAnalysisService(f.documents, analyses, f.classifications, f.labels, f.categories,
                f.catalog, f.classificationService, f.labelService, f.lock, archive, runs, f.views,
                new DocumentPresenter(f.scheduler, f.views, new AnalysisViews(analyses, runs, settings),
                        new InMemoryArchiveTextCheckRepository()), f.persons, f.access,
                settings, f.clock);
    }
}
