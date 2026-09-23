package dev.aegis.shared;

import java.util.Objects;

/**
 * On whose behalf a use case runs: normally the authenticated household member, whom inbound adapters take from the
 * verified session; application services still verify membership through {@link HouseholdAccess}.
 *
 * <p>A {@linkplain #householdProcess(String) household process} is the other kind of actor: a background process of
 * the household itself, such as the review reconciler (ADR-0009), that runs without any member. It is never created
 * from a request, never passes {@link HouseholdAccess#requireMember(Actor)} and has no member id, so it can read only
 * what a service explicitly opens to it through {@link HouseholdAccess#requireReader(Actor)} and can never change a
 * household record or be recorded as the author of a change.
 */
public record Actor(MemberId memberId, String process) {
    public Actor {
        if ((memberId == null) == (process == null)) {
            throw new IllegalArgumentException("an actor is either a member or a household process");
        }
        if (process != null && process.isBlank()) {
            throw new IllegalArgumentException("a household process needs a name");
        }
    }

    public Actor(MemberId memberId) {
        this(Objects.requireNonNull(memberId, "member id"), null);
    }

    /** A named background process of the household that acts without any member. */
    public static Actor householdProcess(String name) {
        return new Actor(null, Objects.requireNonNull(name, "process name"));
    }

    public boolean isHouseholdProcess() {
        return process != null;
    }

    /**
     * The member this actor stands for.
     *
     * @throws NotAuthorizedException for a household process, which never acts as or on behalf of a member
     */
    @Override
    public MemberId memberId() {
        if (memberId == null) {
            throw new NotAuthorizedException("household process " + process + " does not act as a member");
        }
        return memberId;
    }
}
