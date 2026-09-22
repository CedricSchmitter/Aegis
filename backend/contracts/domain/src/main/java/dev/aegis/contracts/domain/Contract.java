package dev.aegis.contracts.domain;

import dev.aegis.shared.ContractId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import dev.aegis.shared.TrashedException;
import dev.aegis.shared.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A household contract and everything Aegis knows about it. Facts are appended, never overwritten: an extraction
 * can only propose, a member decides, and a correction supersedes the observation it replaces. Which value is
 * effective on a day follows from the confirmed facts and their validity periods, so an amendment takes over from
 * its own start date while the original stays readable. A trashed contract keeps every fact (ADR-0010); member
 * changes are refused until it is restored.
 */
public final class Contract {
    public static final int MAX_NAME_LENGTH = 200;

    private final ContractId id;
    private String name;
    private ContractStatus status;
    private final MemberId createdBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<ContractFact> facts = new ArrayList<>();
    private Trash trash;
    private long version;

    private Contract(ContractId id, String name, ContractStatus status, MemberId createdBy, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "contract id");
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "status");
        this.createdBy = Objects.requireNonNull(createdBy, "created by");
        this.createdAt = Objects.requireNonNull(createdAt, "created at");
        this.updatedAt = createdAt;
    }

    public static Contract create(String name, MemberId createdBy, Instant now) {
        return new Contract(ContractId.random(), name, ContractStatus.ACTIVE, createdBy, now);
    }

    /** Rehydration from persistence or an export; facts are restored with their stored review states. */
    public static Contract rehydrate(ContractId id, String name, ContractStatus status, MemberId createdBy,
            Instant createdAt, Instant updatedAt, List<ContractFact> facts, long version) {
        return rehydrate(id, name, status, createdBy, createdAt, updatedAt, facts, null, version);
    }

    /** Rehydration including the trash record; {@code trash} is null for a contract that is not in the trash. */
    public static Contract rehydrate(ContractId id, String name, ContractStatus status, MemberId createdBy,
            Instant createdAt, Instant updatedAt, List<ContractFact> facts, Trash trash, long version) {
        Contract contract = new Contract(id, name, status, createdBy, createdAt);
        contract.updatedAt = updatedAt == null ? createdAt : updatedAt;
        contract.facts.addAll(facts);
        contract.trash = trash;
        contract.version = version;
        return contract;
    }

    public void rename(String newName, Instant now) {
        requireNotTrashed();
        this.name = requireName(newName);
        touch(now);
    }

    public void changeStatus(ContractStatus newStatus, Instant now) {
        requireNotTrashed();
        this.status = Objects.requireNonNull(newStatus, "status");
        touch(now);
    }

    /**
     * Moves the contract to the trash: it leaves lists, published deadlines and review, and keeps every fact with
     * its provenance. Trashing a trashed contract changes nothing.
     *
     * @return whether the contract changed
     */
    public boolean moveToTrash(MemberId by, Instant now, Trash.RecoveryWindow window) {
        if (trash != null) {
            return false;
        }
        trash = Trash.begin(by, now, window);
        touch(now);
        return true;
    }

    /**
     * Brings a trashed contract back exactly as it was.
     *
     * @return whether the contract changed
     */
    public boolean restoreFromTrash(Instant now) {
        if (trash == null) {
            return false;
        }
        trash = null;
        touch(now);
        return true;
    }

    public void requireNotTrashed() {
        if (trash != null) {
            throw new TrashedException("contract", id.toString());
        }
    }

    public boolean isTrashed() {
        return trash != null;
    }

    public Optional<Trash> trash() {
        return Optional.ofNullable(trash);
    }

    /**
     * A member states a fact. It is confirmed at once and supersedes an earlier confirmed fact of the same type
     * with the same validity period; a fact for a different period amends instead of replacing.
     */
    public ContractFact enter(FactType type, FactValue value, ValidityPeriod validity, SourceReference source,
            String note, MemberId by, Instant now) {
        requireNotTrashed();
        ContractFact fact = ContractFact.stated(type, value, validity, by, source, note, now);
        supersedeReplaced(fact);
        facts.add(fact);
        touch(now);
        return fact;
    }

    /**
     * An extraction run proposes a fact. The proposal is always stored as extracted, so no automatic result can
     * change what a member confirmed. A proposal that repeats a known value is not stored again, which keeps
     * reprocessing the same document idempotent.
     */
    public Observation observe(FactType type, FactValue value, ValidityPeriod validity, SourceReference source,
            Confidence confidence, String runRef, Instant now) {
        ContractFact proposal = ContractFact.extracted(type, value, validity, source, confidence, runRef, now);
        Optional<ContractFact> confirmed = facts.stream()
                .filter(existing -> existing.effective() && existing.describesSameAs(proposal)).findFirst();
        if (confirmed.isPresent()) {
            return new Observation(confirmed.get(), ObservationOutcome.ALREADY_CONFIRMED);
        }
        Optional<ContractFact> pending = facts.stream()
                .filter(existing -> existing.pending() && existing.describesSameAs(proposal)).findFirst();
        if (pending.isPresent()) {
            return new Observation(pending.get(), ObservationOutcome.ALREADY_PROPOSED);
        }
        boolean contradicts = facts.stream().anyMatch(existing -> existing.effective() && existing.type() == type
                && existing.validity().overlaps(validity) && !existing.value().sameAs(value));
        facts.add(proposal);
        touch(now);
        return new Observation(proposal,
                contradicts ? ObservationOutcome.CONTRADICTS_CONFIRMED : ObservationOutcome.RECORDED);
    }

    /** A member accepts a proposal; it replaces a confirmed fact for the same period. */
    public ContractFact confirm(FactId factId, MemberId by, Instant now) {
        requireNotTrashed();
        ContractFact fact = require(factId);
        fact.confirm(by, now);
        supersedeReplaced(fact);
        touch(now);
        return fact;
    }

    /** A member decides a value is wrong. The fact stays visible as history and stops being effective. */
    public ContractFact reject(FactId factId, MemberId by, Instant now) {
        requireNotTrashed();
        ContractFact fact = require(factId);
        fact.reject(by, now);
        touch(now);
        return fact;
    }

    /**
     * A member corrects an observation. The correction is a new confirmed fact that keeps the source of the fact
     * it replaces, and the corrected observation becomes superseded history.
     */
    public ContractFact correct(FactId factId, FactValue value, ValidityPeriod validity, String note, MemberId by,
            Instant now) {
        requireNotTrashed();
        ContractFact corrected = require(factId);
        if (corrected.state() == ReviewState.SUPERSEDED) {
            throw new InvalidInputException("contract.fact.superseded", "A superseded fact cannot be corrected");
        }
        ContractFact correction = ContractFact.correcting(corrected, value, validity, by, note, now);
        supersedeReplaced(correction);
        corrected.supersedeBy(correction.id());
        facts.add(correction);
        touch(now);
        return correction;
    }

    /**
     * The fact that describes the contract on that day, or empty when none is confirmed or when confirmed facts
     * contradict each other. A calculation must never guess which of two contradicting values is meant.
     */
    public Optional<ContractFact> effective(FactType type, LocalDate on) {
        List<ContractFact> candidates = effectiveCandidates(type, on);
        if (candidates.isEmpty() || disagree(candidates)) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(0));
    }

    /** Whether confirmed facts of this type contradict each other on that day. */
    public boolean ambiguous(FactType type, LocalDate on) {
        return disagree(effectiveCandidates(type, on));
    }

    /**
     * Every contradiction that needs a decision on that day: confirmed facts that disagree, and extractions that
     * contradict a confirmed fact.
     */
    public List<FactConflict> conflicts(LocalDate on) {
        List<FactConflict> conflicts = new ArrayList<>();
        Map<FactType, List<ContractFact>> pendingByType = new EnumMap<>(FactType.class);
        for (ContractFact fact : facts) {
            if (fact.pending()) {
                pendingByType.computeIfAbsent(fact.type(), type -> new ArrayList<>()).add(fact);
            }
        }
        for (FactType type : FactType.values()) {
            List<ContractFact> candidates = effectiveCandidates(type, on);
            if (candidates.isEmpty()) {
                continue;
            }
            ContractFact established = candidates.get(0);
            List<ContractFact> disagreeing = candidates.stream().skip(1)
                    .filter(other -> !other.value().sameAs(established.value())).toList();
            if (!disagreeing.isEmpty()) {
                conflicts.add(new FactConflict(type, FactConflict.ConflictKind.CONFIRMED_AGAINST_CONFIRMED,
                        established, disagreeing));
            }
            List<ContractFact> contradicting = pendingByType.getOrDefault(type, List.of()).stream()
                    .filter(pending -> pending.validity().overlaps(established.validity()))
                    .filter(pending -> !pending.value().sameAs(established.value())).toList();
            if (!contradicting.isEmpty()) {
                conflicts.add(new FactConflict(type, FactConflict.ConflictKind.EXTRACTED_AGAINST_CONFIRMED,
                        established, contradicting));
            }
        }
        return List.copyOf(conflicts);
    }

    /** All observations of a type, newest observation first; corrections and superseded values included. */
    public List<ContractFact> history(FactType type) {
        return facts.stream().filter(fact -> fact.type() == type)
                .sorted(Comparator.comparing((ContractFact fact) -> fact.provenance().observedAt()).reversed())
                .toList();
    }

    public Optional<ContractFact> fact(FactId factId) {
        return facts.stream().filter(fact -> fact.id().equals(factId)).findFirst();
    }

    /** Facts waiting for a decision. */
    public List<ContractFact> pendingFacts() {
        return facts.stream().filter(ContractFact::pending).toList();
    }

    /** The documents facts of this contract were taken from, in the order they were first referenced. */
    public List<SourceReference> sources() {
        Map<String, SourceReference> unique = new LinkedHashMap<>();
        for (ContractFact fact : facts) {
            fact.provenance().sourceReference()
                    .ifPresent(source -> unique.putIfAbsent(source.documentId().toString(), source));
        }
        return List.copyOf(unique.values());
    }

    public ContractId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ContractStatus status() {
        return status;
    }

    public MemberId createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<ContractFact> facts() {
        return List.copyOf(facts);
    }

    public long version() {
        return version;
    }

    /** Confirmed facts covering the day whose validity starts latest; an amendment therefore wins over the original. */
    private List<ContractFact> effectiveCandidates(FactType type, LocalDate on) {
        List<ContractFact> covering = facts.stream()
                .filter(fact -> fact.type() == type && fact.effective() && fact.validity().covers(on))
                .sorted(Comparator.comparing((ContractFact fact) -> fact.validity().effectiveFrom()).reversed())
                .toList();
        if (covering.isEmpty()) {
            return List.of();
        }
        LocalDate latestStart = covering.get(0).validity().effectiveFrom();
        return covering.stream().filter(fact -> fact.validity().effectiveFrom().equals(latestStart)).toList();
    }

    private static boolean disagree(List<ContractFact> candidates) {
        return candidates.size() > 1
                && candidates.stream().anyMatch(other -> !other.value().sameAs(candidates.get(0).value()));
    }

    /** A newly confirmed fact replaces confirmed facts of the same type for exactly the same period. */
    private void supersedeReplaced(ContractFact fact) {
        for (ContractFact existing : facts) {
            if (existing != fact && existing.effective() && existing.type() == fact.type()
                    && existing.validity().sameRange(fact.validity())) {
                existing.supersedeBy(fact.id());
            }
        }
    }

    private ContractFact require(FactId factId) {
        return fact(factId).orElseThrow(() -> new NotFoundException("contract.fact", factId.toString()));
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("contract.name.missing", "Name is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new InvalidInputException("contract.name.tooLong", "Name exceeds " + MAX_NAME_LENGTH);
        }
        return trimmed;
    }

    /** What happened to an automatic proposal. */
    public enum ObservationOutcome {
        /** Stored as a proposal waiting for review. */
        RECORDED,
        /** Stored as a proposal that contradicts a confirmed fact; the confirmed value stays effective. */
        CONTRADICTS_CONFIRMED,
        /** A member already confirmed exactly this value; nothing was stored. */
        ALREADY_CONFIRMED,
        /** The same proposal is already waiting for review; nothing was stored. */
        ALREADY_PROPOSED
    }

    /** The stored or already known fact for a proposal, with what happened to it. */
    public record Observation(ContractFact fact, ObservationOutcome outcome) {
    }
}
