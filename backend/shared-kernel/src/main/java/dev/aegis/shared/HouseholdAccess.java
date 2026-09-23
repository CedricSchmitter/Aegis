package dev.aegis.shared;

/**
 * Authorization contract used by every application service. The identity context implements it; other contexts
 * depend only on this interface so that REST, jobs, search and export enforce the same rule.
 */
public interface HouseholdAccess {

    /**
     * Ensures the actor is an active member of the shared household. A household process never is one.
     *
     * @throws NotAuthorizedException when the actor is unknown, inactive or a household process
     */
    void requireMember(Actor actor);

    /**
     * Ensures the actor may read shared household records through a query that is open to household processes: an
     * active member, or a {@linkplain Actor#householdProcess(String) household process}. Only the queries a
     * background process needs call this (ADR-0009); every other query and every command requires a member.
     *
     * @throws NotAuthorizedException when the actor is neither an active member nor a household process
     */
    default void requireReader(Actor actor) {
        requireMember(actor);
    }
}
