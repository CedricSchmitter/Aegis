package dev.aegis.contracts.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One observed statement about a contract: a typed value for a validity period, with its provenance and review
 * state. Observations are never edited in place. A correction records a new fact that supersedes the old one, so
 * the value a member saw when they decided stays readable.
 */
public final class ContractFact {
    public static final int MAX_NOTE_LENGTH = 1000;

    private final FactId id;
    private final FactType type;
    private final FactValue value;
    private final ValidityPeriod validity;
    private final FactProvenance provenance;
    private final FactId corrects;
    private final String note;
    private ReviewState state;
    private MemberId decidedBy;
    private Instant decidedAt;
    private FactId supersededBy;

    private ContractFact(FactId id, FactType type, FactValue value, ValidityPeriod validity,
            FactProvenance provenance, FactId corrects, String note, ReviewState state) {
        this.id = Objects.requireNonNull(id, "fact id");
        this.type = Objects.requireNonNull(type, "fact type");
        this.value = requireMatchingValue(type, value);
        this.validity = Objects.requireNonNull(validity, "validity");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.corrects = corrects;
        this.note = normalizeNote(note);
        this.state = Objects.requireNonNull(state, "review state");
    }

    /** A member states a fact. It counts as confirmed immediately, because a member decided it. */
    public static ContractFact stated(FactType type, FactValue value, ValidityPeriod validity, MemberId by,
            SourceReference source, String note, Instant now) {
        ContractFact fact = new ContractFact(FactId.random(), type, value, validity,
                FactProvenance.manual(by, source, now), null, note, ReviewState.CONFIRMED);
        fact.decidedBy = by;
        fact.decidedAt = now;
        return fact;
    }

    /** An extraction run proposes a fact. It always waits for a decision, whatever its confidence is. */
    public static ContractFact extracted(FactType type, FactValue value, ValidityPeriod validity,
            SourceReference source, Confidence confidence, String runRef, Instant now) {
        return new ContractFact(FactId.random(), type, value, validity,
                FactProvenance.automatic(source, confidence, runRef, now), null, null, ReviewState.EXTRACTED);
    }

    /** A member corrects an earlier observation; the new fact keeps the source of the fact it replaces. */
    public static ContractFact correcting(ContractFact corrected, FactValue value, ValidityPeriod validity,
            MemberId by, String note, Instant now) {
        ContractFact fact = new ContractFact(FactId.random(), corrected.type, value, validity,
                FactProvenance.manual(by, corrected.provenance.source(), now), corrected.id, note,
                ReviewState.CONFIRMED);
        fact.decidedBy = by;
        fact.decidedAt = now;
        return fact;
    }

    /** Rehydration from persistence or an export; states are restored as they were stored. */
    public static ContractFact rehydrate(FactId id, FactType type, FactValue value, ValidityPeriod validity,
            FactProvenance provenance, ReviewState state, MemberId decidedBy, Instant decidedAt,
            FactId supersededBy, FactId corrects, String note) {
        ContractFact fact = new ContractFact(id, type, value, validity, provenance, corrects, note, state);
        fact.decidedBy = decidedBy;
        fact.decidedAt = decidedAt;
        fact.supersededBy = supersededBy;
        return fact;
    }

    void confirm(MemberId by, Instant now) {
        if (state == ReviewState.CONFIRMED) {
            throw new InvalidInputException("contract.fact.alreadyConfirmed", "Fact is already confirmed");
        }
        if (state == ReviewState.SUPERSEDED) {
            throw new InvalidInputException("contract.fact.superseded", "A superseded fact cannot be confirmed");
        }
        this.state = ReviewState.CONFIRMED;
        this.decidedBy = Objects.requireNonNull(by, "member");
        this.decidedAt = Objects.requireNonNull(now, "now");
    }

    void reject(MemberId by, Instant now) {
        if (state == ReviewState.REJECTED) {
            throw new InvalidInputException("contract.fact.alreadyRejected", "Fact is already rejected");
        }
        if (state == ReviewState.SUPERSEDED) {
            throw new InvalidInputException("contract.fact.superseded", "A superseded fact cannot be rejected");
        }
        this.state = ReviewState.REJECTED;
        this.decidedBy = Objects.requireNonNull(by, "member");
        this.decidedAt = Objects.requireNonNull(now, "now");
    }

    void supersedeBy(FactId newer) {
        this.state = ReviewState.SUPERSEDED;
        this.supersededBy = Objects.requireNonNull(newer, "newer fact");
    }

    public boolean effective() {
        return state.effective();
    }

    public boolean pending() {
        return state.pending();
    }

    public boolean describesSameAs(ContractFact other) {
        return type == other.type && validity.sameRange(other.validity) && value.sameAs(other.value);
    }

    public FactId id() {
        return id;
    }

    public FactType type() {
        return type;
    }

    public FactValue value() {
        return value;
    }

    public ValidityPeriod validity() {
        return validity;
    }

    public FactProvenance provenance() {
        return provenance;
    }

    public ReviewState state() {
        return state;
    }

    public Optional<MemberId> decidedBy() {
        return Optional.ofNullable(decidedBy);
    }

    public Optional<Instant> decidedAt() {
        return Optional.ofNullable(decidedAt);
    }

    public Optional<FactId> supersededBy() {
        return Optional.ofNullable(supersededBy);
    }

    public Optional<FactId> corrects() {
        return Optional.ofNullable(corrects);
    }

    public String note() {
        return note;
    }

    private static FactValue requireMatchingValue(FactType type, FactValue value) {
        Objects.requireNonNull(value, "value");
        if (value.kind() != type.valueKind()) {
            throw new InvalidInputException("contract.fact.value.kind",
                    "Fact " + type + " expects a " + type.valueKind() + " value");
        }
        return value;
    }

    private static String normalizeNote(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new InvalidInputException("contract.fact.note.tooLong", "Note exceeds " + MAX_NOTE_LENGTH);
        }
        return trimmed;
    }
}
