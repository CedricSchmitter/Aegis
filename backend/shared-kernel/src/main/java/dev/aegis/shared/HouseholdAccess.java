package dev.aegis.shared;

/**
 * Authorization contract used by every application service. The identity context implements it; other contexts
 * depend only on this interface so that REST, jobs, search and export enforce the same rule.
 */
public interface HouseholdAccess {

    /**
     * Ensures the actor is an active member of the shared household.
     *
     * @throws NotAuthorizedException when the actor is unknown or inactive
     */
    void requireMember(Actor actor);
}
