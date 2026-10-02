package dev.aegis.review.application.api;

import dev.aegis.shared.Actor;

/**
 * Reconciles the review inbox with the producers without a member opening it (ADR-0009): the recurring
 * background job calls it so a new card and its notices reach both members shortly after their source changed.
 */
public interface ReviewReconciliation {
    /**
     * The identity background reconciliation runs as: a household process, not a member. It may read the producers'
     * queries opened to household processes and the member directory, create and supersede cards and publish their
     * notices; it can neither act on nor dismiss a card, nor change any household record.
     */
    Actor RECONCILER = Actor.householdProcess("review-reconciler");

    /** What one reconciliation found and changed; {@code candidates} counts distinct current fingerprints. */
    record Report(int candidates, int created, int superseded) {
    }

    /**
     * Creates a card, and one notice per active member, for every new producer candidate and supersedes open cards
     * that no producer reports any more. Safe to run concurrently with itself and with a member opening the inbox:
     * each card and its notices are created exactly once.
     *
     * @throws dev.aegis.shared.NotAuthorizedException unless the actor is {@link #RECONCILER} or an active member
     */
    Report reconcile(Actor actor);
}
