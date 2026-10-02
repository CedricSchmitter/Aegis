package dev.aegis.documents.domain.pages;

import dev.aegis.documents.domain.Checksum;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One request of the page editor (ADR-0024). Its id is chosen by the client, so a repeated request finds the
 * operation it already started. The new documents get ids derived from the operation id and their position, which
 * makes registering an output that already exists recognizable after a crash: every output is created once.
 */
public final class PageEdit {

    public enum State { REQUESTED, COMPLETED, FAILED }

    /** The new pages scanned for the request, stored durably in ingress until the operation has finished. */
    public record Scan(String path, Checksum checksum, int pageCount) {
        public Scan {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(checksum, "checksum");
        }
    }

    /**
     * A created output. {@code existing} is true when identical content was already stored, for example because the
     * same plan was carried out before; {@code document} is then that earlier document.
     */
    public record Result(int output, DocumentId document, boolean existing) {
    }

    private final UUID id;
    private final MemberId requestedBy;
    private final Instant requestedAt;
    private final PageEditPlan plan;
    private final Scan scan;
    private State state;
    private int attempts;
    private String failureCode;
    private Instant finishedAt;
    private List<Result> results;
    private final long version;

    private PageEdit(UUID id, MemberId requestedBy, Instant requestedAt, PageEditPlan plan, Scan scan, State state,
            int attempts, String failureCode, Instant finishedAt, List<Result> results, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.scan = scan;
        this.state = Objects.requireNonNull(state, "state");
        this.attempts = attempts;
        this.failureCode = failureCode;
        this.finishedAt = finishedAt;
        this.results = List.copyOf(results);
        this.version = version;
    }

    /** A new request; {@code scan} is null when the plan uses no new pages. */
    public static PageEdit requested(UUID id, MemberId by, Instant now, PageEditPlan plan, Scan scan) {
        if (plan.usesScan() && scan == null) {
            throw new IllegalArgumentException("plan uses new pages but no scan is stored");
        }
        return new PageEdit(id, by, now, plan, scan, State.REQUESTED, 0, null, null, List.of(), 0);
    }

    /** Rehydration from persistence; performs no business validation. */
    public static PageEdit rehydrate(UUID id, MemberId requestedBy, Instant requestedAt, PageEditPlan plan, Scan scan,
            State state, int attempts, String failureCode, Instant finishedAt, List<Result> results, long version) {
        return new PageEdit(id, requestedBy, requestedAt, plan, scan, state, attempts, failureCode, finishedAt,
                results, version);
    }

    /** The id the output at {@code position} is registered with: the same on every attempt. */
    public DocumentId resultIdFor(int position) {
        if (position < 0 || position >= plan.outputs().size()) {
            throw new IllegalArgumentException("no output " + position);
        }
        byte[] name = ("aegis-page-edit:" + id + ":" + position).getBytes(StandardCharsets.UTF_8);
        return new DocumentId(UUID.nameUUIDFromBytes(name));
    }

    /**
     * Whether a repeated request asks for the same thing, so it may be answered with this operation. Whether new pages
     * are part of it follows from the plan.
     */
    public boolean sameRequest(PageEditPlan otherPlan) {
        return plan.equals(otherPlan);
    }

    public boolean isPending() {
        return state == State.REQUESTED;
    }

    /**
     * Records an attempt that did not finish. A retryable failure keeps the operation requested while attempts
     * remain; otherwise it fails with the code and waits for the member, who can start a new request.
     *
     * @return true when another automatic attempt is allowed
     */
    public boolean recordFailure(String code, boolean retryable, int maxAttempts, Instant now) {
        requirePending();
        attempts++;
        failureCode = Objects.requireNonNull(code, "code");
        if (retryable && attempts < maxAttempts) {
            return true;
        }
        state = State.FAILED;
        finishedAt = now;
        return false;
    }

    public void complete(List<Result> created, Instant now) {
        requirePending();
        if (created.size() != plan.outputs().size()) {
            throw new IllegalArgumentException("expected " + plan.outputs().size() + " results");
        }
        results = List.copyOf(created);
        failureCode = null;
        state = State.COMPLETED;
        finishedAt = now;
    }

    private void requirePending() {
        if (state != State.REQUESTED) {
            throw new IllegalStateException("page edit " + id + " already finished as " + state);
        }
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

    public PageEditPlan plan() {
        return plan;
    }

    public Optional<Scan> scan() {
        return Optional.ofNullable(scan);
    }

    public State state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public Optional<String> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public List<Result> results() {
        return results;
    }

    public long version() {
        return version;
    }
}
