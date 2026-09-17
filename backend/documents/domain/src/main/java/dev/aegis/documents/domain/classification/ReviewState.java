package dev.aegis.documents.domain.classification;

/** Review state of a label assignment. Only effective states take part in filtering and display as labels. */
public enum ReviewState {
    /** Automatic proposal waiting for a member's decision; not effective. */
    SUGGESTED(false, 0),
    /** Automatic assignment applied under an explicit policy and not yet reviewed; effective. */
    APPLIED(true, 2),
    /** Set or confirmed by a member; effective. */
    CONFIRMED(true, 3),
    /** A member rejected or removed an automatic value; remembered so later runs do not bring it back. */
    REJECTED(false, 1);

    private final boolean effective;
    private final int precedence;

    ReviewState(boolean effective, int precedence) {
        this.effective = effective;
        this.precedence = precedence;
    }

    public boolean effective() {
        return effective;
    }

    /** Which state survives when two assignments collapse into one (for example after a label merge). */
    public boolean outranks(ReviewState other) {
        return precedence > other.precedence;
    }
}
