package dev.aegis.contracts.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public contract other contexts use for confirmed cancellation deadlines, for example to create a
 * source-linked review task with a reminder (issue #63). Only contracts whose confirmed facts are sufficient
 * appear here: an unreviewed extraction, a rejected value or contradicting sources produce no deadline at all.
 *
 * <p>The evaluation day is passed in, not taken from a clock inside this context, so a caller that catches up
 * after downtime decides which household day it is calculating for.
 */
public interface ContractDeadlineQuery {

    /**
     * A contract whose cancellation deadline follows from confirmed facts.
     *
     * @param contractId stable contract identity for a source link
     * @param name household name of the contract
     * @param counterparty confirmed counterparty, or null when none is confirmed
     * @param contractEnd confirmed contract end, or null when none is confirmed
     * @param cancellationDeadline the contractual deadline, never a planned work or reminder date
     * @param basis which confirmed rule produced the deadline
     * @param basedOnFactIds the facts the deadline rests on
     * @param sourceDocumentId the document the deadline rests on, for a source link; null when a member stated it
     *        without one
     * @param status the contract lifecycle state, so a cancelled contract needs no further review
     * @param contractVersion the version the deadline was read at; a reconciliation compares against it
     * @param updatedAt when the contract last changed
     */
    record ContractDeadlineView(ContractId contractId, String name, String counterparty, LocalDate contractEnd,
            LocalDate cancellationDeadline, String basis, List<UUID> basedOnFactIds, DocumentId sourceDocumentId,
            String status, long contractVersion, Instant updatedAt) {

        public ContractDeadlineView {
            basedOnFactIds = List.copyOf(basedOnFactIds);
        }
    }

    /**
     * Every contract with a sufficient confirmed cancellation deadline on that household day, ordered by
     * deadline. Contracts without confirmed rules are absent rather than guessed.
     */
    List<ContractDeadlineView> confirmedDeadlines(Actor actor, LocalDate on);

    /** The confirmed deadline of one contract, or empty when its facts are insufficient on that day. */
    Optional<ContractDeadlineView> confirmedDeadline(Actor actor, ContractId contractId, LocalDate on);
}
