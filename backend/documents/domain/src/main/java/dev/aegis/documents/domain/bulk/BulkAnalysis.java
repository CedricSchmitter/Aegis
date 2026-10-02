package dev.aegis.documents.domain.bulk;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An explicitly started classification of existing documents. The document list is fixed when a member
 * starts it; the run then requests the documents' classification runs in bounded batches, one item at a time, and
 * records how each ended.
 *
 * <p>Invariants: an item is requested at most once, so a repeated or revived step never classifies a document twice;
 * at most {@link #batchSize()} items are in flight; nothing is requested after a cancellation or a stop; the run ends
 * only when no item is in flight any more, except a cancellation, which takes the in-flight items back at once.
 */
public final class BulkAnalysis {
    public static final int MAX_BATCH = 10;

    public enum State {
        /** Requesting and following the documents' runs. */
        RUNNING,
        /** Every document was requested or skipped and every run ended. */
        COMPLETED,
        /** A member cancelled it; documents not yet classified stay unclassified. */
        CANCELLED,
        /** The AI boundary refused further requests (for example the requester lost AI access); see the stop code. */
        STOPPED
    }

    public enum ItemState {
        PENDING,
        /** Its classification run was requested with {@link Item#generation()}. */
        REQUESTED,
        FINISHED,
        /** Not requested because it no longer qualified (trashed, already classified, ...); see the code. */
        SKIPPED,
        CANCELLED
    }

    /** How the run of a finished item ended. */
    public enum Outcome {
        CLASSIFIED,
        FAILED,
        /** The requester may not use the configured AI provider: no failure. */
        NOT_CLASSIFIED,
        /** A member requested the document's analysis again; that request owns the result. */
        SUPERSEDED
    }

    /** One document of the run. {@code code} is the skip reason or the run's failure code. */
    public record Item(DocumentId documentId, int position, ItemState state, int generation, Outcome outcome,
            String code, Instant updatedAt) {
        public Item {
            Objects.requireNonNull(documentId, "document id");
            Objects.requireNonNull(state, "state");
        }

        Item with(ItemState newState, int newGeneration, Outcome newOutcome, String newCode, Instant now) {
            return new Item(documentId, position, newState, newGeneration, newOutcome, newCode, now);
        }
    }

    /** Item counts by state, and finished items by outcome. */
    public record Progress(int total, Map<ItemState, Integer> states, Map<Outcome, Integer> outcomes) {
        public Progress {
            states = Map.copyOf(states);
            outcomes = Map.copyOf(outcomes);
        }

        public int count(ItemState state) {
            return states.getOrDefault(state, 0);
        }

        public int count(Outcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }

    private final UUID id;
    private final MemberId requestedBy;
    private final Instant requestedAt;
    private final BulkScope scope;
    private final int batchSize;
    private State state;
    private String stopCode;
    private MemberId cancelledBy;
    private Instant cancelledAt;
    private Instant finishedAt;
    private final Map<DocumentId, Item> items;
    private final Set<DocumentId> changed = new LinkedHashSet<>();
    private final long version;
    private final boolean fresh;

    private BulkAnalysis(UUID id, MemberId requestedBy, Instant requestedAt, BulkScope scope, int batchSize,
            State state, String stopCode, MemberId cancelledBy, Instant cancelledAt, Instant finishedAt,
            List<Item> items, long version, boolean fresh) {
        this.id = Objects.requireNonNull(id, "id");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requested by");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requested at");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (batchSize < 1 || batchSize > MAX_BATCH) {
            throw new IllegalArgumentException("batch size must be 1 to " + MAX_BATCH);
        }
        this.batchSize = batchSize;
        this.state = Objects.requireNonNull(state, "state");
        this.stopCode = stopCode;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
        this.finishedAt = finishedAt;
        this.items = new LinkedHashMap<>();
        items.stream().sorted(Comparator.comparingInt(Item::position))
                .forEach(item -> this.items.put(item.documentId(), item));
        this.version = version;
        this.fresh = fresh;
    }

    /** Starts a run over {@code documents} in the given order; at least one document is required. */
    public static BulkAnalysis start(UUID id, MemberId by, Instant now, BulkScope scope, List<DocumentId> documents,
            int batchSize) {
        Set<DocumentId> distinct = new LinkedHashSet<>(documents);
        if (distinct.isEmpty()) {
            throw new InvalidInputException("analysis.bulk.empty", "No document in this scope needs classification");
        }
        List<Item> items = new ArrayList<>();
        int position = 0;
        for (DocumentId document : distinct) {
            items.add(new Item(document, position++, ItemState.PENDING, 0, null, null, now));
        }
        BulkAnalysis bulk = new BulkAnalysis(id, by, now, scope, batchSize, State.RUNNING, null, null, null, null,
                items, 0, true);
        bulk.changed.addAll(distinct);
        return bulk;
    }

    public static BulkAnalysis rehydrate(UUID id, MemberId requestedBy, Instant requestedAt, BulkScope scope,
            int batchSize, State state, String stopCode, MemberId cancelledBy, Instant cancelledAt,
            Instant finishedAt, List<Item> items, long version) {
        return new BulkAnalysis(id, requestedBy, requestedAt, scope, batchSize, state, stopCode, cancelledBy,
                cancelledAt, finishedAt, items, version, false);
    }

    public boolean running() {
        return state == State.RUNNING;
    }

    /** Running but refusing further requests until the in-flight runs ended. */
    public boolean stopping() {
        return state == State.RUNNING && stopCode != null;
    }

    public List<Item> inFlight() {
        return items.values().stream().filter(item -> item.state() == ItemState.REQUESTED).toList();
    }

    /** The next pending documents that fit into the batch, in their original order. */
    public List<DocumentId> nextToRequest() {
        if (state != State.RUNNING || stopCode != null) {
            return List.of();
        }
        int free = batchSize - inFlight().size();
        return items.values().stream().filter(item -> item.state() == ItemState.PENDING).limit(Math.max(0, free))
                .map(Item::documentId).toList();
    }

    /** The document's classification run was requested with {@code generation}. */
    public void requested(DocumentId document, int generation, Instant now) {
        if (nextToRequest().stream().noneMatch(document::equals)) {
            throw new IllegalStateException("document " + document + " cannot be requested by bulk run " + id);
        }
        update(document, ItemState.REQUESTED, generation, null, null, now);
    }

    /** The document no longer qualified when its turn came; {@code reason} is a stable code. */
    public void skipped(DocumentId document, String reason, Instant now) {
        Item item = require(document);
        if (state != State.RUNNING || item.state() != ItemState.PENDING) {
            throw new IllegalStateException("document " + document + " is not pending in bulk run " + id);
        }
        update(document, ItemState.SKIPPED, 0, null, Objects.requireNonNull(reason), now);
    }

    /** The run of an in-flight document ended. */
    public void finished(DocumentId document, Outcome outcome, String code, Instant now) {
        Item item = require(document);
        if (item.state() != ItemState.REQUESTED) {
            throw new IllegalStateException("document " + document + " is not in flight in bulk run " + id);
        }
        update(document, ItemState.FINISHED, item.generation(), Objects.requireNonNull(outcome), code, now);
    }

    /**
     * Refuses further requests, for example because the AI boundary no longer serves the requester. Runs in flight
     * are still followed; the bulk run ends as {@link State#STOPPED} once they ended.
     */
    public void stop(String code) {
        if (state == State.RUNNING && stopCode == null) {
            stopCode = Objects.requireNonNull(code);
        }
    }

    /**
     * Cancels the run: pending and in-flight documents become cancelled.
     *
     * @return the items that were in flight, whose runs the caller cancels and whose requests it takes back; empty
     *         when the run had already ended
     */
    public List<Item> cancel(MemberId by, Instant now) {
        if (state != State.RUNNING) {
            return List.of();
        }
        List<Item> inFlight = inFlight();
        for (Item item : List.copyOf(items.values())) {
            if (item.state() == ItemState.PENDING || item.state() == ItemState.REQUESTED) {
                update(item.documentId(), ItemState.CANCELLED, item.generation(), null, null, now);
            }
        }
        state = State.CANCELLED;
        cancelledBy = Objects.requireNonNull(by);
        cancelledAt = now;
        finishedAt = now;
        return inFlight;
    }

    /**
     * Ends the run when nothing is in flight and nothing more will be requested.
     *
     * @return whether the run ended now
     */
    public boolean settle(Instant now) {
        if (state != State.RUNNING || !inFlight().isEmpty()) {
            return false;
        }
        boolean pending = items.values().stream().anyMatch(item -> item.state() == ItemState.PENDING);
        if (pending && stopCode == null) {
            return false;
        }
        state = stopCode == null ? State.COMPLETED : State.STOPPED;
        finishedAt = now;
        return true;
    }

    public Progress progress() {
        Map<ItemState, Integer> states = new EnumMap<>(ItemState.class);
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        for (Item item : items.values()) {
            states.merge(item.state(), 1, Integer::sum);
            if (item.outcome() != null) {
                outcomes.merge(item.outcome(), 1, Integer::sum);
            }
        }
        return new Progress(items.size(), states, outcomes);
    }

    private Item require(DocumentId document) {
        Item item = items.get(document);
        if (item == null) {
            throw new IllegalArgumentException("document " + document + " is not part of bulk run " + id);
        }
        return item;
    }

    private void update(DocumentId document, ItemState newState, int generation, Outcome outcome, String code,
            Instant now) {
        items.put(document, require(document).with(newState, generation, outcome, code, now));
        changed.add(document);
    }

    public UUID id() {
        return id;
    }

    public MemberId requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public BulkScope scope() {
        return scope;
    }

    public int batchSize() {
        return batchSize;
    }

    public State state() {
        return state;
    }

    public Optional<String> stopCode() {
        return Optional.ofNullable(stopCode);
    }

    public Optional<MemberId> cancelledBy() {
        return Optional.ofNullable(cancelledBy);
    }

    public Optional<Instant> cancelledAt() {
        return Optional.ofNullable(cancelledAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    /** All items in their original order. */
    public List<Item> items() {
        return List.copyOf(items.values());
    }

    /** Items changed since the run was loaded or started, for the repository. */
    public List<Item> changedItems() {
        return changed.stream().map(items::get).toList();
    }

    public long version() {
        return version;
    }

    /** Started in this transaction and not stored yet. */
    public boolean isNew() {
        return fresh;
    }
}
