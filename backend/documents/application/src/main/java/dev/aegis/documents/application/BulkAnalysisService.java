package dev.aegis.documents.application;

import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.BulkAnalysisCommands;
import dev.aegis.documents.application.api.BulkAnalysisPreview;
import dev.aegis.documents.application.api.BulkAnalysisQueries;
import dev.aegis.documents.application.api.BulkAnalysisView;
import dev.aegis.documents.application.api.BulkAnalysisView.DocumentRef;
import dev.aegis.documents.application.api.BulkAnalysisView.PlacementGroup;
import dev.aegis.documents.application.api.BulkAnalysisView.ReasonGroup;
import dev.aegis.documents.application.api.ClassificationView;
import dev.aegis.documents.application.api.PlacementView;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.DocumentCatalog.Criteria;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.analysis.AnalysisTypes.Status;
import dev.aegis.documents.domain.analysis.DocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.bulk.BulkAnalysis;
import dev.aegis.documents.domain.bulk.BulkAnalysis.Item;
import dev.aegis.documents.domain.bulk.BulkAnalysis.ItemState;
import dev.aegis.documents.domain.bulk.BulkAnalysis.Outcome;
import dev.aegis.documents.domain.bulk.BulkAnalysisRepository;
import dev.aegis.documents.domain.bulk.BulkScope;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bulk classification of existing documents (ADR-0022): the preview with its cost estimate, the explicit
 * start, the cancellation, the views, and the steps of the run's persisted job.
 *
 * <p>A run fixes its document list at the start and requests the documents one by one through
 * {@link BulkAnalysisRequests}, the same path a member's selection uses, so generations, idempotency keys, the
 * source-version check, the effect ledger and the apply policy of ADR-0004/ADR-0005 are unchanged. At most
 * {@link BulkAnalysisSettings#batchSize()} runs are in flight; while one of them waits for the next budget day, the
 * run requests nothing more. Commands, queries and {@link #advance} are wrapped in a transaction by the composition
 * root.
 */
public class BulkAnalysisService implements BulkAnalysisCommands, BulkAnalysisQueries, BulkAnalysisDriver {
    static final int RECENT = 5;
    /** Finished runs whose results the review inbox still groups. */
    static final int ATTENTION_RUNS = 5;
    /** Extra candidates read beyond the bound, because documents with a run in flight are left out afterwards. */
    private static final int SLACK = 50;

    private final BulkAnalysisRepository bulks;
    private final DocumentRepository documents;
    private final DocumentAnalysisRepository analyses;
    private final DocumentCatalog catalog;
    private final CategoryRepository categories;
    private final LabelRepository labels;
    private final ClassificationViews classificationViews;
    private final AnalysisViews analysisViews;
    private final AnalysisRuns runs;
    private final BulkAnalysisRequests requests;
    private final ProcessingScheduler scheduler;
    private final HouseholdAccess access;
    private final BulkAnalysisSettings settings;
    private final Clock clock;

    public BulkAnalysisService(BulkAnalysisRepository bulks, DocumentRepository documents,
            DocumentAnalysisRepository analyses, DocumentCatalog catalog, CategoryRepository categories,
            LabelRepository labels, ClassificationViews classificationViews, AnalysisViews analysisViews,
            AnalysisRuns runs, BulkAnalysisRequests requests, ProcessingScheduler scheduler, HouseholdAccess access,
            BulkAnalysisSettings settings, Clock clock) {
        this.bulks = bulks;
        this.documents = documents;
        this.analyses = analyses;
        this.catalog = catalog;
        this.categories = categories;
        this.labels = labels;
        this.classificationViews = classificationViews;
        this.analysisViews = analysisViews;
        this.runs = runs;
        this.requests = requests;
        this.scheduler = scheduler;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    // Preview and start ---------------------------------------------------------------------------------------------

    @Override
    public BulkAnalysisPreview preview(Actor actor, ScopeRequest request) {
        access.requireMember(actor);
        BulkScope scope = scope(request);
        CategoryTree tree = categories.load();
        Candidates candidates = candidates(scope, tree);
        AnalysisRuns.CostEstimate estimate = runs.estimate(actor);
        List<DocumentId> sample = candidates.included().stream().limit(BulkAnalysisPreview.SAMPLE).toList();
        return new BulkAnalysisPreview(scopeView(scope, tree), candidates.included().size(), candidates.limited(),
                settings.maxDocuments(), refs(sample), BulkCosts.estimate(estimate, candidates.included().size()),
                estimate.refusal(), bulks.findRunning().map(BulkAnalysis::id).orElse(null));
    }

    @Override
    public BulkAnalysisView start(Actor actor, ScopeRequest request, int expectedDocuments) {
        access.requireMember(actor);
        BulkScope scope = scope(request);
        String refusal = runs.estimate(actor).refusal();
        if (refusal != null) {
            // The run is requested on the starting member's behalf, so a member who may not use AI cannot start it.
            throw new InvalidInputException(refusal, "The configured AI provider is not available for this member");
        }
        if (bulks.findRunning().isPresent()) {
            throw new InvalidInputException("analysis.bulk.active", "Another bulk analysis is running");
        }
        CategoryTree tree = categories.load();
        Candidates candidates = candidates(scope, tree);
        if (candidates.included().size() != expectedDocuments) {
            throw new InvalidInputException("analysis.bulk.changed", "The documents in this scope changed",
                    Map.of("documents", String.valueOf(candidates.included().size())));
        }
        Instant now = clock.instant();
        BulkAnalysis bulk = BulkAnalysis.start(UUID.randomUUID(), actor.memberId(), now, scope,
                candidates.included(), Math.min(settings.batchSize(), BulkAnalysis.MAX_BATCH));
        bulks.save(bulk);
        // The job row commits with the run, so a started run is always followed, also after a restart.
        scheduler.scheduleBulkAnalysis(bulk.id(), now);
        return view(load(bulk.id()), tree);
    }

    private record Candidates(List<DocumentId> included, boolean limited) {
    }

    /**
     * Documents in the scope that were never classified, oldest first and bounded. A document whose analysis is
     * requested right now is left out: its run already belongs to someone.
     */
    private Candidates candidates(BulkScope scope, CategoryTree tree) {
        DocumentCatalog.Page page = catalog.neverClassified(criteria(scope, tree), settings.maxDocuments() + SLACK);
        Map<DocumentId, DocumentAnalysis> stored = analyses.findAll(page.ids());
        List<String> keys = stored.values().stream().filter(analysis -> analysis.status() == Status.REQUESTED)
                .map(analysis -> AnalysisRuns.key(analysis.documentId(), analysis.generation())).toList();
        Map<String, AnalysisRuns.RunStatus> statuses = keys.isEmpty() ? Map.of() : runs.latest(keys);
        List<DocumentId> eligible = new ArrayList<>();
        long busy = 0;
        for (DocumentId id : page.ids()) {
            DocumentAnalysis analysis = stored.get(id);
            AnalysisRuns.RunStatus run = analysis == null || analysis.status() != Status.REQUESTED ? null
                    : statuses.get(AnalysisRuns.key(id, analysis.generation()));
            if (run != null && run.active()) {
                busy++;
            } else {
                eligible.add(id);
            }
        }
        long total = page.total() - busy;
        List<DocumentId> included = eligible.stream().limit(settings.maxDocuments()).toList();
        return new Candidates(included, total > included.size());
    }

    private static Criteria criteria(BulkScope scope, CategoryTree tree) {
        return switch (scope.kind()) {
            case ALL -> Criteria.everything();
            case UNSORTED -> new Criteria(null, null, true, Map.of(), Set.of());
            case FOLDER -> {
                CategoryId folder = tree.require(new CategoryId(scope.folderId())).id();
                Set<CategoryId> selected = scope.includeSubfolders() ? tree.subtree(folder) : Set.of(folder);
                yield new Criteria(null, selected.stream().map(CategoryId::value).collect(Collectors.toSet()), false,
                        Map.of(), Set.of());
            }
        };
    }

    private static BulkScope scope(ScopeRequest request) {
        if (request == null || request.kind() == null) {
            throw new InvalidInputException("analysis.bulk.scope", "A bulk analysis needs a scope");
        }
        try {
            return new BulkScope(BulkScope.Kind.valueOf(request.kind().toUpperCase(Locale.ROOT)), request.folderId(),
                    request.includeSubfolders());
        } catch (IllegalArgumentException unknown) {
            throw new InvalidInputException("analysis.bulk.scope", "Unknown bulk analysis scope");
        }
    }

    // Cancellation --------------------------------------------------------------------------------------------------

    @Override
    public BulkAnalysisView cancel(Actor actor, UUID id) {
        access.requireMember(actor);
        BulkAnalysis bulk = bulks.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!bulk.running()) {
            return view(bulk, categories.load());
        }
        Instant now = clock.instant();
        List<Item> inFlight = bulk.cancel(actor.memberId(), now);
        if (!inFlight.isEmpty()) {
            Map<String, AnalysisRuns.RunStatus> statuses = runs.latest(inFlight.stream()
                    .map(item -> AnalysisRuns.key(item.documentId(), item.generation())).toList());
            for (Item item : inFlight) {
                AnalysisRuns.RunStatus run = statuses.get(AnalysisRuns.key(item.documentId(), item.generation()));
                // The run first, then the analysis: the same lock order as the run's completion transaction.
                if (run != null && run.active()) {
                    runs.cancel(actor, run.runId());
                }
                requests.withdraw(item.documentId(), item.generation());
            }
        }
        bulks.save(bulk);
        return view(load(id), categories.load());
    }

    // Job step ------------------------------------------------------------------------------------------------------

    @Override
    public Step advance(UUID bulkId) {
        Optional<BulkAnalysis> found = bulks.findByIdForUpdate(bulkId);
        if (found.isEmpty() || !found.get().running()) {
            return new Done();
        }
        BulkAnalysis bulk = found.get();
        Instant now = clock.instant();
        Instant waitingUntil = follow(bulk, now);
        if (waitingUntil == null) {
            requestNext(bulk, now);
        }
        boolean ended = bulk.settle(now);
        bulks.save(bulk);
        if (ended) {
            return new Done();
        }
        Instant next = now.plus(settings.pollInterval());
        if (waitingUntil != null) {
            // Waiting for the next budget day: look again when it starts, at the latest after maxWait, so a member's
            // own cancellation or retry of a waiting run is noticed.
            Instant wake = waitingUntil.isBefore(now.plus(settings.maxWait())) ? waitingUntil
                    : now.plus(settings.maxWait());
            next = wake.isAfter(next) ? wake : next;
        }
        return new Continue(next);
    }

    /**
     * Records the outcome of every in-flight run that ended.
     *
     * @return the earliest time a run waiting for the next budget day continues, or {@code null}
     */
    private Instant follow(BulkAnalysis bulk, Instant now) {
        List<Item> inFlight = bulk.inFlight();
        if (inFlight.isEmpty()) {
            return null;
        }
        Map<DocumentId, DocumentAnalysis> stored = analyses.findAll(inFlight.stream().map(Item::documentId).toList());
        Map<String, AnalysisRuns.RunStatus> statuses = runs.latest(inFlight.stream()
                .map(item -> AnalysisRuns.key(item.documentId(), item.generation())).toList());
        Instant waitingUntil = null;
        for (Item item : inFlight) {
            DocumentAnalysis analysis = stored.get(item.documentId());
            AnalysisRuns.RunStatus run = statuses.get(AnalysisRuns.key(item.documentId(), item.generation()));
            if (analysis == null || analysis.generation() != item.generation()
                    || analysis.status() == Status.NOT_REQUESTED) {
                bulk.finished(item.documentId(), Outcome.SUPERSEDED, null, now);
            } else if (analysis.status() == Status.COMPLETED) {
                bulk.finished(item.documentId(), Outcome.CLASSIFIED, null, now);
            } else if (run == null) {
                bulk.finished(item.documentId(), Outcome.FAILED, "ai.run.missing", now);
            } else if (run.active()) {
                if (waitsForBudget(run) && (waitingUntil == null || run.nextAttemptAt().isBefore(waitingUntil))) {
                    waitingUntil = run.nextAttemptAt();
                }
            } else if ("SUCCEEDED".equals(run.state())) {
                bulk.finished(item.documentId(), Outcome.CLASSIFIED, null, now);
            } else if (AnalysisRuns.MEMBER_NOT_ALLOWED.equals(run.failureCode())) {
                // The requester lost AI access: no failure, but nothing more can run on their behalf.
                bulk.finished(item.documentId(), Outcome.NOT_CLASSIFIED, run.failureCode(), now);
                bulk.stop(run.failureCode());
            } else {
                bulk.finished(item.documentId(), Outcome.FAILED, Optional.ofNullable(run.failureCode())
                        .orElse("ai.run." + run.state().toLowerCase(Locale.ROOT)), now);
            }
        }
        return waitingUntil;
    }

    private static boolean waitsForBudget(AnalysisRuns.RunStatus run) {
        return "WAITING_RETRY".equals(run.state()) && AnalysisRuns.DAILY_LIMIT.equals(run.failureCode())
                && run.nextAttemptAt() != null;
    }

    /**
     * Requests the next documents on behalf of the member who started the run until the batch is full; a skipped
     * document frees its place for the next one.
     */
    private void requestNext(BulkAnalysis bulk, Instant now) {
        Actor requester = new Actor(bulk.requestedBy());
        for (List<DocumentId> next = bulk.nextToRequest(); !next.isEmpty(); next = bulk.nextToRequest()) {
            if (!requestEach(bulk, requester, next, now)) {
                return;
            }
        }
    }

    /** False when the AI boundary refused and the run stops. */
    private boolean requestEach(BulkAnalysis bulk, Actor requester, List<DocumentId> next, Instant now) {
        for (DocumentId id : next) {
            BulkAnalysisRequests.Result result;
            try {
                result = requests.requestForBulk(requester, id);
            } catch (NotAuthorizedException noLongerMember) {
                bulk.stop("auth.forbidden");
                return false;
            }
            switch (result) {
                case BulkAnalysisRequests.Requested requested -> bulk.requested(id, requested.generation(), now);
                case BulkAnalysisRequests.Skipped skipped -> bulk.skipped(id, skipped.reason(), now);
                case BulkAnalysisRequests.Refused refused -> {
                    bulk.stop(refused.code());
                    return false;
                }
            }
        }
        return true;
    }

    // Queries -------------------------------------------------------------------------------------------------------

    @Override
    public List<BulkAnalysisView> recent(Actor actor) {
        access.requireMember(actor);
        CategoryTree tree = categories.load();
        List<BulkAnalysis> recent = new ArrayList<>(bulks.findRecent(RECENT));
        bulks.findRunning().filter(running -> recent.stream().noneMatch(bulk -> bulk.id().equals(running.id())))
                .ifPresent(running -> recent.addFirst(running));
        return recent.stream().map(bulk -> view(bulk, tree)).toList();
    }

    @Override
    public BulkAnalysisView get(Actor actor, UUID id) {
        access.requireMember(actor);
        return view(load(id), categories.load());
    }

    @Override
    public List<Attention> attention(Actor actor) {
        // Open to household processes: the review reconciler groups these results into one card (ADR-0009).
        access.requireReader(actor);
        CategoryTree tree = categories.load();
        return bulks.findRecent(ATTENTION_RUNS + 1).stream().filter(bulk -> !bulk.running())
                .limit(ATTENTION_RUNS).map(bulk -> view(bulk, tree))
                .filter(view -> view.needsReview() > 0 || failing(view) > 0)
                .map(view -> new Attention(view.id(), view.state(), view.finishedAt(), view.total(),
                        view.classified(), view.needsReview(), failing(view)))
                .toList();
    }

    private static int failing(BulkAnalysisView view) {
        return view.failures().stream().mapToInt(ReasonGroup::documents).sum();
    }

    /**
     * Progress from the run's items; review needs, folder proposals and failures from the documents as they are now,
     * so a member's decision since the run is reflected and a document requested again later is no longer counted.
     */
    private BulkAnalysisView view(BulkAnalysis bulk, CategoryTree tree) {
        BulkAnalysis.Progress progress = bulk.progress();
        List<Item> items = bulk.items();
        List<DocumentId> ids = items.stream().map(Item::documentId).toList();
        Map<DocumentId, Document> loaded = documents.findByIds(ids).stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        List<Item> finished = items.stream().filter(item -> item.state() == ItemState.FINISHED
                && (item.outcome() == Outcome.CLASSIFIED || item.outcome() == Outcome.FAILED)
                && loaded.containsKey(item.documentId()) && !loaded.get(item.documentId()).isTrashed()).toList();
        Map<DocumentId, ClassificationView> classifications = classificationViews.viewsOf(
                finished.stream().map(Item::documentId).toList(), tree, labels.load());
        Map<DocumentId, AnalysisViews.Parts> parts = analysisViews.partsOf(
                finished.stream().map(item -> loaded.get(item.documentId())).toList(), classifications);

        List<DocumentRef> review = new ArrayList<>();
        int needsReview = 0;
        Map<String, List<DocumentId>> failures = new LinkedHashMap<>();
        Map<String, List<DocumentId>> placements = new LinkedHashMap<>();
        Map<String, PlacementView.Suggestion> suggestions = new LinkedHashMap<>();
        for (Item item : finished) {
            AnalysisView analysis = parts.get(item.documentId()).analysis();
            if (analysis.generation() != item.generation()) {
                continue;
            }
            if (AnalysisView.NEEDS_REVIEW.equals(analysis.state())) {
                needsReview++;
                if (review.size() < BulkAnalysisView.MAX_LISTED) {
                    review.add(ref(loaded.get(item.documentId())));
                }
            } else if (AnalysisView.FAILED.equals(analysis.state())) {
                failures.computeIfAbsent(Optional.ofNullable(analysis.failureCode()).orElse("unknown"),
                        code -> new ArrayList<>()).add(item.documentId());
            }
            PlacementView.Suggestion suggestion = classifications.getOrDefault(item.documentId(),
                    ClassificationView.UNSORTED).placementSuggestion();
            if (suggestion != null) {
                placements.computeIfAbsent(suggestion.key(), key -> new ArrayList<>()).add(item.documentId());
                suggestions.putIfAbsent(suggestion.key(), suggestion);
            }
        }
        List<PlacementGroup> placementGroups = placements.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<DocumentId>> e) -> -e.getValue().size())
                        .thenComparing(e -> String.join("/", suggestions.get(e.getKey()).path())))
                .map(e -> new PlacementGroup(e.getKey(), suggestions.get(e.getKey()).path(),
                        suggestions.get(e.getKey()).newFolders(), e.getValue().size(),
                        ref(loaded.get(e.getValue().getFirst()))))
                .toList();
        Map<String, List<DocumentId>> skips = new LinkedHashMap<>();
        items.stream().filter(item -> item.state() == ItemState.SKIPPED).forEach(item ->
                skips.computeIfAbsent(item.code(), code -> new ArrayList<>()).add(item.documentId()));
        return new BulkAnalysisView(bulk.id(), bulk.state().name(), bulk.stopCode().orElse(null),
                scopeView(bulk.scope(), tree), bulk.requestedBy(), bulk.requestedAt(), bulk.cancelledBy().orElse(null),
                bulk.cancelledAt().orElse(null), bulk.finishedAt().orElse(null), progress.total(),
                progress.count(ItemState.PENDING), progress.count(ItemState.REQUESTED),
                progress.count(Outcome.CLASSIFIED), needsReview, progress.count(Outcome.FAILED),
                progress.count(Outcome.NOT_CLASSIFIED), progress.count(ItemState.SKIPPED),
                progress.count(ItemState.CANCELLED), progress.count(Outcome.SUPERSEDED),
                bulk.running() ? waitingUntil(bulk) : null, placementGroups, groups(failures, loaded),
                groups(skips, loaded), review);
    }

    private Instant waitingUntil(BulkAnalysis bulk) {
        List<Item> inFlight = bulk.inFlight();
        if (inFlight.isEmpty()) {
            return null;
        }
        return runs.latest(inFlight.stream().map(item -> AnalysisRuns.key(item.documentId(), item.generation()))
                        .toList()).values().stream().filter(BulkAnalysisService::waitsForBudget)
                .map(AnalysisRuns.RunStatus::nextAttemptAt).min(Comparator.naturalOrder()).orElse(null);
    }

    private static List<ReasonGroup> groups(Map<String, List<DocumentId>> byCode, Map<DocumentId, Document> loaded) {
        return byCode.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<DocumentId>> e) -> -e.getValue().size())
                        .thenComparing(Map.Entry::getKey))
                .map(e -> new ReasonGroup(e.getKey(), e.getValue().size(), e.getValue().stream()
                        .filter(loaded::containsKey).limit(BulkAnalysisView.MAX_EXAMPLES)
                        .map(id -> ref(loaded.get(id))).toList()))
                .toList();
    }

    private List<DocumentRef> refs(List<DocumentId> ids) {
        Map<DocumentId, Document> loaded = documents.findByIds(ids).stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        return ids.stream().filter(loaded::containsKey).map(id -> ref(loaded.get(id))).toList();
    }

    private static DocumentRef ref(Document document) {
        return new DocumentRef(document.id(), document.title());
    }

    private static BulkAnalysisView.Scope scopeView(BulkScope scope, CategoryTree tree) {
        List<String> path = scope.folderId() == null ? List.of()
                : tree.find(new CategoryId(scope.folderId())).map(folder -> tree.pathNames(folder.id()))
                        .orElse(List.of());
        return new BulkAnalysisView.Scope(scope.kind().name(), scope.folderId(), scope.includeSubfolders(), path);
    }

    private BulkAnalysis load(UUID id) {
        return bulks.findById(id).orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("bulkAnalysis", String.valueOf(id));
    }
}
