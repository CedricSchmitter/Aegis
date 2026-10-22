package dev.aegis.shared;

import java.util.Objects;

/**
 * Who made a recorded change (ADR-0029). Documents and tasks keep their own history, but both name the author the
 * same way, so the household reads one vocabulary.
 *
 * <ul>
 *   <li>{@code MEMBER}: a member's own command; {@code member} is set.</li>
 *   <li>{@code AUTOMATION}: deterministic household automation such as contract deadlines; {@code reference} names the
 *       automation and {@code member} the member whose action triggered it, when there was one.</li>
 *   <li>{@code AI_RUN}: the result of a bounded AI run (ADR-0003); {@code reference} is the run id and {@code member}
 *       the member who requested the run, when one did.</li>
 * </ul>
 */
public record ChangeActor(Kind kind, MemberId member, String reference) {

    public enum Kind {
        MEMBER, AUTOMATION, AI_RUN
    }

    public ChangeActor {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.MEMBER && (member == null || reference != null)) {
            throw new IllegalArgumentException("a member change names the member and nothing else");
        }
        if (kind != Kind.MEMBER && (reference == null || reference.isBlank())) {
            throw new IllegalArgumentException("an automatic change names its automation or run");
        }
    }

    public static ChangeActor member(MemberId member) {
        return new ChangeActor(Kind.MEMBER, Objects.requireNonNull(member, "member"), null);
    }

    /** The member behind {@code actor}; a household process is never the author of a change. */
    public static ChangeActor of(Actor actor) {
        return member(actor.memberId());
    }

    public static ChangeActor automation(String name, MemberId triggeredBy) {
        return new ChangeActor(Kind.AUTOMATION, triggeredBy, name);
    }

    public static ChangeActor aiRun(String runId, MemberId requestedBy) {
        return new ChangeActor(Kind.AI_RUN, requestedBy, runId);
    }
}
