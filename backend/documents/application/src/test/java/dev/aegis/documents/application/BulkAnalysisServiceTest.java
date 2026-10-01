package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.BulkAnalysisDriver.Continue;
import dev.aegis.documents.application.BulkAnalysisDriver.Done;
import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.AutomaticClassification.LabelClaim;
import dev.aegis.documents.application.api.AutomaticClassification.PlacementClaim;
import dev.aegis.documents.application.api.AutomaticClassification.Proposal;
import dev.aegis.documents.application.api.BulkAnalysisCommands.ScopeRequest;
import dev.aegis.documents.application.api.BulkAnalysisPreview;
import dev.aegis.documents.application.api.BulkAnalysisQueries;
import dev.aegis.documents.application.api.BulkAnalysisView;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Trigger;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.classification.DocumentClassification;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bulk classification (issue #115) against in-memory repositories and a controlled AI boundary: preview and cost,
 * the explicit start, bounded batches, repeated steps after a restart, the budget wait, stale and skipped documents,
 * cancellation, refusals, authorization and the grouped results.
 */
class BulkAnalysisServiceTest {
    private static final String TEXT = """
            Telekom Deutschland GmbH
            Rechnungsdatum: 17.09.2026
            Ihre Rechnung für MagentaZuhause Internet""";
    private static final AtomicInteger PAPERLESS_IDS = new AtomicInteger(500);
    private static final ScopeRequest ALL = new ScopeRequest("ALL", null, false);
    private static final Duration POLL = Duration.ofSeconds(10);
    private static final Duration MAX_WAIT = Duration.ofMinutes(15);

    private CatalogFixture f;
    private InMemoryDocumentAnalysisRepository analyses;
    private TestDoubles.FakeArchive archive;
    private TestDoubles.RecordingRuns runs;
    private DocumentAnalysisService analysis;
    private InMemoryBulkAnalysisRepository bulks;
    private BulkAnalysisService service;
    private LabelView telekom;
    private LabelView invoice;
    private LabelView internet;

    @BeforeEach
    void setUp() {
        f = new CatalogFixture();
        analyses = new InMemoryDocumentAnalysisRepository();
        f.catalog.withAnalyses(analyses);
        archive = new TestDoubles.FakeArchive();
        runs = new TestDoubles.RecordingRuns();
        AnalysisSettings settings = TestDoubles.analysisSettings();
        AnalysisViews analysisViews = new AnalysisViews(analyses, runs, settings);
        DocumentPresenter presenter = new DocumentPresenter(f.scheduler, f.views, analysisViews,
                new InMemoryArchiveTextCheckRepository());
        analysis = new DocumentAnalysisService(f.documents, analyses, f.classifications, f.labels, f.categories,
                f.catalog, f.classificationService, f.labelService, f.lock, archive, runs, f.views, presenter,
                f.persons, f.access, settings, f.clock);
        bulks = new InMemoryBulkAnalysisRepository();
        service = service(f.access);
        telekom = f.label("ORGANIZATION", "Telekom");
        telekom = f.labelService.replaceAliases(f.actor, telekom.id(), List.of("Telekom Deutschland GmbH"),
                telekom.version());
        invoice = f.label("DOCUMENT_TYPE", "Rechnung");
        internet = f.label("TOPIC", "Internet");
    }

    private BulkAnalysisService service(dev.aegis.shared.HouseholdAccess access) {
        return new BulkAnalysisService(bulks, f.documents, analyses, f.catalog, f.categories, f.labels, f.views,
                new AnalysisViews(analyses, runs, TestDoubles.analysisSettings()), runs, analysis, f.scheduler,
                access, new BulkAnalysisSettings(100, 2, POLL, MAX_WAIT), f.clock);
    }

    private Document archived(String title) {
        Document document = f.document(title);
        int paperlessId = PAPERLESS_IDS.incrementAndGet();
        document.markArchived(paperlessId, CatalogFixture.NOW);
        f.documents.save(document);
        archive.texts.put(paperlessId, TEXT);
        return document;
    }

    private List<Document> archived(int count) {
        List<Document> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            created.add(archived("Bestand " + i));
        }
        // The order a run requests them in: oldest upload first, then by id (all share the fixture's upload time).
        created.sort(java.util.Comparator.comparing(document -> document.id().value()));
        return created;
    }

    private UUID start(int expected) {
        return service.start(f.actor, ALL, expected).id();
    }

    private DocumentAnalysis analysisOf(Document document) {
        return analyses.findById(document.id()).orElseThrow();
    }

    private long classificationVersion(Document document) {
        return f.classifications.findById(document.id()).map(DocumentClassification::version).orElse(0L);
    }

    private static LabelClaim claim(UUID id, double confidence, String evidence) {
        return new LabelClaim(id, null, confidence, evidence);
    }

    /** The model's answer arrives and its run succeeds; {@code doubtful} leaves everything as a suggestion. */
    private void classify(Document document, boolean doubtful, PlacementClaim placement) {
        int generation = analysisOf(document).generation();
        Proposal proposal = new Proposal(generation, classificationVersion(document), null, null, null, null, false,
                claim(invoice.id(), 0.95, "Ihre Rechnung"), claim(telekom.id(), 0.97, "Telekom Deutschland GmbH"),
                List.of(claim(internet.id(), 0.9, "MagentaZuhause Internet")), "Internet", List.of(), placement,
                doubtful ? List.of("POOR_OCR") : List.of());
        analysis.apply(f.actor, analysis.assess(f.actor, document.id(), proposal), "ai-run:test", "claude-sonnet-5");
        runs.finish(document.id(), generation, "SUCCEEDED", null, null);
    }

    private BulkAnalysisView view(UUID id) {
        return service.get(f.actor, id);
    }

    @Test
    void thePreviewIncludesOnlyArchivedNeverClassifiedDocumentsOfTheScopeAndEstimatesTheCost() {
        CategoryView folder = f.folder("Verträge", null);
        Document filed = archived("Im Ordner");
        f.classificationService.place(f.actor, filed.id(), folder.id(), 0);
        Document unsorted = archived("Unsortiert");
        Document classified = archived("Schon klassifiziert");
        analysis.requestAnalysis(f.actor, classified.id(), 0);
        classify(classified, false, null);
        f.document("Noch nicht archiviert");
        Document running = archived("Läuft gerade");
        analysis.requestAnalysis(f.actor, running.id(), 0);

        BulkAnalysisPreview all = service.preview(f.actor, ALL);
        assertThat(all.documents()).isEqualTo(2);
        assertThat(all.sample()).extracting(BulkAnalysisView.DocumentRef::id)
                .containsExactlyInAnyOrder(filed.id(), unsorted.id());
        assertThat(all.limited()).isFalse();
        assertThat(all.refusal()).isNull();
        assertThat(all.runningId()).isNull();
        assertThat(all.cost().basis()).isEqualTo("AVERAGE");
        assertThat(all.cost().perDocument()).isEqualByComparingTo("0.01");
        assertThat(all.cost().total()).isEqualByComparingTo("0.02");
        assertThat(all.cost().maximum()).isEqualByComparingTo("0.10");
        assertThat(all.cost().documentsToday()).as("what today's budget pays for").isEqualTo(25);
        assertThat(all.cost().days()).isEqualTo(1);

        BulkAnalysisPreview inFolder = service.preview(f.actor, new ScopeRequest("FOLDER", folder.id(), true));
        assertThat(inFolder.documents()).isEqualTo(1);
        assertThat(inFolder.scope().folderPath()).containsExactly("Verträge");
        assertThat(service.preview(f.actor, new ScopeRequest("UNSORTED", null, false)).sample())
                .extracting(BulkAnalysisView.DocumentRef::id).containsExactly(unsorted.id());
        assertThat(bulks.findRunning()).as("a preview changes nothing").isEmpty();
        assertThat(runs.requested).hasSize(2);
    }

    @Test
    void theCostIsSpreadOverBudgetDaysAndUsesTheRunBudgetWithoutEnoughHistory() {
        archived(30);
        runs.averageCost = null;
        runs.spentToday = new BigDecimal("0.20");
        BulkAnalysisPreview preview = service.preview(f.actor, ALL);
        assertThat(preview.cost().basis()).isEqualTo("RUN_BUDGET");
        assertThat(preview.cost().perDocument()).isEqualByComparingTo("0.05");
        assertThat(preview.cost().remainingToday()).isEqualByComparingTo("0.05");
        assertThat(preview.cost().documentsToday()).isEqualTo(1);
        // 29 more documents at 5 per day of 0.25: six more days after today.
        assertThat(preview.cost().days()).isEqualTo(7);

        runs.dailyLimit = new BigDecimal("0.01");
        runs.spentToday = BigDecimal.ZERO;
        assertThat(service.preview(f.actor, ALL).cost().days()).as("the limit cannot pay one run").isNull();
    }

    @Test
    void onlyAMemberWhoMayUseAiStartsARunBoundToThePreviewAndOnlyOneRunsAtATime() {
        archived(3);
        MemberId cedric = f.cedric;
        runs.notAllowed.add(cedric);
        assertThat(service.preview(new Actor(cedric), ALL).refusal()).isEqualTo(AnalysisRuns.MEMBER_NOT_ALLOWED);
        assertThatThrownBy(() -> service.start(new Actor(cedric), ALL, 3))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo(AnalysisRuns.MEMBER_NOT_ALLOWED));
        assertThatThrownBy(() -> service.start(f.actor, ALL, 2))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis.bulk.changed"));
        assertThat(bulks.findRunning()).isEmpty();
        assertThat(f.scheduler.bulkSteps).isEmpty();

        UUID id = start(3);
        assertThat(f.scheduler.bulkSteps).containsEntry(id, CatalogFixture.NOW);
        assertThat(runs.requested).as("the job requests, not the start").isEmpty();
        assertThat(view(id).pending()).isEqualTo(3);
        assertThat(service.preview(f.actor, ALL).runningId()).isEqualTo(id);
        assertThatThrownBy(() -> service.start(f.actor, ALL, 3))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis.bulk.active"));
    }

    @Test
    void stepsRequestBoundedBatchesNeverRequestADocumentTwiceAndCompleteTheRun() {
        List<Document> documents = archived(5);
        UUID id = start(5);

        assertThat(service.advance(id)).isEqualTo(new Continue(CatalogFixture.NOW.plus(POLL)));
        assertThat(runs.requested).hasSize(2);
        // A revived job or a restart repeats the step: nothing is requested twice while the batch is in flight.
        service.advance(id);
        service.advance(id);
        assertThat(runs.requested).hasSize(2);
        assertThat(view(id).inFlight()).isEqualTo(2);
        assertThat(analysisOf(documents.get(0)).snapshot().trigger()).isEqualTo(Trigger.BULK);
        assertThat(analysisOf(documents.get(0)).snapshot().requestedBy()).isEqualTo(f.lisa);

        classify(documents.get(0), false, null);
        classify(documents.get(1), true, null);
        service.advance(id);
        assertThat(runs.requested).hasSize(4);
        BulkAnalysisView progress = view(id);
        assertThat(progress.classified()).isEqualTo(2);
        assertThat(progress.needsReview()).isEqualTo(1);
        assertThat(progress.reviewDocuments()).extracting(BulkAnalysisView.DocumentRef::id)
                .containsExactly(documents.get(1).id());

        classify(documents.get(2), false, null);
        classify(documents.get(3), false, null);
        service.advance(id);
        classify(documents.get(4), false, null);
        assertThat(service.advance(id)).isEqualTo(new Done());
        assertThat(service.advance(id)).as("an ended run is not continued").isEqualTo(new Done());

        BulkAnalysisView done = view(id);
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.classified()).isEqualTo(5);
        assertThat(done.finishedAt()).isEqualTo(CatalogFixture.NOW);
        assertThat(runs.requested).hasSize(5);
        assertThat(documents).allSatisfy(document ->
                assertThat(analysisOf(document).generation()).as("one generation per document").isEqualTo(1));
    }

    @Test
    void aRunWaitingForTheNextBudgetDayRequestsNothingMoreUntilThen() {
        List<Document> documents = archived(4);
        UUID id = start(4);
        service.advance(id);
        Instant midnight = Instant.parse("2026-09-17T22:00:00Z");
        runs.waitForRetry(documents.get(0).id(), 1, "BUDGET_EXHAUSTED", AnalysisRuns.DAILY_LIMIT, midnight);
        classify(documents.get(1), false, null);

        BulkAnalysisDriver.Step step = service.advance(id);
        assertThat(step).isEqualTo(new Continue(CatalogFixture.NOW.plus(MAX_WAIT)));
        assertThat(runs.requested).hasSize(2);
        BulkAnalysisView waiting = view(id);
        assertThat(waiting.waitingForBudgetUntil()).isEqualTo(midnight);
        assertThat(waiting.failed()).isZero();
        assertThat(waiting.state()).isEqualTo("RUNNING");

        classify(documents.get(0), false, null);
        service.advance(id);
        assertThat(runs.requested).hasSize(4);
        assertThat(view(id).waitingForBudgetUntil()).isNull();
    }

    @Test
    void aDocumentClassifiedOrTrashedSinceTheStartIsSkippedInsteadOfClassifiedAgain() {
        List<Document> documents = archived(3);
        UUID id = start(3);
        analysis.requestAnalysis(f.actor, documents.get(0).id(), 0);
        classify(documents.get(0), false, null);
        Document trashed = f.documents.findById(documents.get(1).id()).orElseThrow();
        trashed.moveToTrash(dev.aegis.shared.Trash.begin(f.lisa, CatalogFixture.NOW,
                dev.aegis.shared.Trash.RecoveryWindow.DEFAULT));
        f.documents.save(trashed);

        service.advance(id);
        assertThat(runs.requested).hasSize(2);
        BulkAnalysisView view = view(id);
        assertThat(view.skipped()).isEqualTo(2);
        assertThat(view.skips()).extracting(BulkAnalysisView.ReasonGroup::code)
                .containsExactlyInAnyOrder("analysis.classified", "document.trashed");
        assertThat(view.inFlight()).isEqualTo(1);
        assertThat(analysisOf(documents.get(0)).generation()).isEqualTo(1);
    }

    @Test
    void aMemberEditDuringTheRunMakesItsResultStaleAndTheFailureIsSummarized() {
        List<Document> documents = archived(2);
        UUID id = start(2);
        service.advance(id);
        Document edited = documents.get(0);
        long analyzedVersion = classificationVersion(edited);
        f.classificationService.setLabels(f.actor, edited.id(), List.of(internet.id()), analyzedVersion);
        assertThat(analysis.current(f.actor, edited.id(), analyzedVersion, 1))
                .as("the completion transaction refuses the stale result").isFalse();
        runs.finish(edited.id(), 1, "FAILED", "STALE_SOURCE", "ai.source.stale");
        classify(documents.get(1), false, null);

        assertThat(service.advance(id)).isEqualTo(new Done());
        BulkAnalysisView done = view(id);
        assertThat(done.failed()).isEqualTo(1);
        assertThat(done.failures()).singleElement().satisfies(group -> {
            assertThat(group.code()).isEqualTo("ai.source.stale");
            assertThat(group.documents()).isEqualTo(1);
            assertThat(group.examples()).extracting(BulkAnalysisView.DocumentRef::id).containsExactly(edited.id());
        });
        assertThat(service.attention(f.actor)).singleElement().satisfies(attention -> {
            assertThat(attention.id()).isEqualTo(id);
            assertThat(attention.failed()).isEqualTo(1);
            assertThat(attention.classified()).isEqualTo(1);
        });
    }

    @Test
    void cancellingStopsRequestsCancelsTheRunsInFlightAndRestoresTheirDocuments() {
        List<Document> documents = archived(5);
        UUID id = start(5);
        service.advance(id);
        classify(documents.get(0), false, null);
        service.advance(id);
        assertThat(runs.requested).hasSize(3);

        BulkAnalysisView cancelled = service.cancel(new Actor(f.cedric), id);
        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo(f.cedric);
        assertThat(cancelled.cancelled()).isEqualTo(4);
        assertThat(cancelled.classified()).isEqualTo(1);
        assertThat(runs.cancelled).hasSize(2);
        for (Document document : documents.subList(1, 3)) {
            DocumentAnalysis restored = analysisOf(document);
            assertThat(restored.status()).isEqualTo(Status.NOT_REQUESTED);
            assertThat(restored.accepts(1)).as("a late answer is refused").isFalse();
        }
        assertThat(analysisOf(documents.get(0)).status()).as("an applied result stays").isEqualTo(Status.COMPLETED);
        assertThat(service.advance(id)).isEqualTo(new Done());
        assertThat(runs.requested).hasSize(3);
        assertThat(service.cancel(f.actor, id).cancelledBy()).as("cancelling again changes nothing")
                .isEqualTo(f.cedric);
        assertThat(service.preview(f.actor, ALL).documents()).as("the rest can be started again").isEqualTo(4);
    }

    @Test
    void aRefusalOfTheAiBoundaryStopsTheRunWithoutMarkingTheDocuments() {
        List<Document> documents = archived(3);
        UUID id = start(3);
        runs.refusal = "ai.notConfigured";

        assertThat(service.advance(id)).isEqualTo(new Done());
        BulkAnalysisView stopped = view(id);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.stopCode()).isEqualTo("ai.notConfigured");
        assertThat(stopped.pending()).isEqualTo(3);
        assertThat(documents).allSatisfy(document ->
                assertThat(analyses.findById(document.id())).as("no analysis became unavailable").isEmpty());
    }

    @Test
    void aRequesterWhoLostAiAccessEndsTheRunAsNotClassifiedWithoutFailures() {
        List<Document> documents = archived(4);
        UUID id = start(4);
        service.advance(id);
        runs.finish(documents.get(0).id(), 1, "FAILED", "NOT_AUTHORIZED", AnalysisRuns.MEMBER_NOT_ALLOWED);
        runs.finish(documents.get(1).id(), 1, "FAILED", "NOT_AUTHORIZED", AnalysisRuns.MEMBER_NOT_ALLOWED);

        assertThat(service.advance(id)).isEqualTo(new Done());
        BulkAnalysisView stopped = view(id);
        assertThat(stopped.state()).isEqualTo("STOPPED");
        assertThat(stopped.stopCode()).isEqualTo(AnalysisRuns.MEMBER_NOT_ALLOWED);
        assertThat(stopped.notClassified()).isEqualTo(2);
        assertThat(stopped.failures()).isEmpty();
        assertThat(runs.requested).hasSize(2);
        assertThat(service.attention(f.actor)).isEmpty();
    }

    @Test
    void finishedRunsGroupIdenticalFolderProposalsForOneReviewCard() {
        List<Document> documents = archived(3);
        UUID id = start(3);
        PlacementClaim contracts = new PlacementClaim(null, null, List.of("Verträge", "Internet"), 0.9,
                "Ihre Rechnung");
        service.advance(id);
        classify(documents.get(0), false, contracts);
        classify(documents.get(1), false, contracts);
        service.advance(id);
        classify(documents.get(2), false, null);
        service.advance(id);

        BulkAnalysisView done = view(id);
        assertThat(done.state()).isEqualTo("COMPLETED");
        assertThat(done.placementGroups()).singleElement().satisfies(group -> {
            assertThat(group.path()).containsExactly("Verträge", "Internet");
            assertThat(group.newFolders()).containsExactly("Verträge", "Internet");
            assertThat(group.documents()).isEqualTo(2);
        });
        assertThat(done.needsReview()).isEqualTo(2);
        assertThat(service.attention(f.actor)).singleElement().satisfies(attention -> {
            assertThat(attention.needsReview()).isEqualTo(2);
            assertThat(attention.total()).isEqualTo(3);
        });
        AnalysisView reviewed = new AnalysisViews(analyses, runs, TestDoubles.analysisSettings())
                .partsOf(f.documents.findById(documents.get(0).id()).orElseThrow(),
                        f.views.viewOf(documents.get(0).id())).analysis();
        assertThat(reviewed.trigger()).isEqualTo("BULK");
    }

    @Test
    void onlyMembersUseBulkRunsAndTheReviewReconcilerOnlyReadsTheirResults() {
        archived(1);
        Actor stranger = new Actor(MemberId.random());
        assertThatThrownBy(() -> service.preview(stranger, ALL)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.start(stranger, ALL, 1)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.recent(stranger)).isInstanceOf(NotAuthorizedException.class);
        UUID id = start(1);
        assertThatThrownBy(() -> service.cancel(stranger, id)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.get(stranger, id)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> service.attention(stranger)).isInstanceOf(NotAuthorizedException.class);

        Actor reconciler = Actor.householdProcess("review-reconciler");
        BulkAnalysisService asReconciler = service(new dev.aegis.shared.HouseholdAccess() {
            @Override
            public void requireMember(Actor actor) {
                f.access.requireMember(actor);
            }

            @Override
            public void requireReader(Actor actor) {
                if (!actor.isHouseholdProcess()) {
                    requireMember(actor);
                }
            }
        });
        assertThat(asReconciler.attention(reconciler)).isEmpty();
        assertThatThrownBy(() -> asReconciler.cancel(reconciler, id)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> asReconciler.preview(reconciler, ALL)).isInstanceOf(NotAuthorizedException.class);
        assertThat(view(id).state()).isEqualTo("RUNNING");
    }

    @Test
    void anUnknownScopeIsRefused() {
        assertThatThrownBy(() -> service.preview(f.actor, new ScopeRequest("EVERYTHING", null, false)))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis.bulk.scope"));
        assertThatThrownBy(() -> service.preview(f.actor, new ScopeRequest("FOLDER", null, false)))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        e -> assertThat(e.code()).isEqualTo("analysis.bulk.scope"));
    }
}
