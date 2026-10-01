package dev.aegis.planning.application;

import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractDeadlineQuery.ContractDeadlineView;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.planning.application.api.CancellationDeadlineReconciliation;
import dev.aegis.planning.application.api.CancellationReminderPublication;
import dev.aegis.planning.application.ports.CancellationReminderScheduler;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.planning.domain.CancellationReview;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskSource;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.TaskId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic, AI-free bridge from the contracts public contract into planning and the notification inbox.
 * Transaction interception in the composition root makes task persistence and reminder scheduling atomic.
 */
public class CancellationDeadlineService
        implements CancellationDeadlineReconciliation, CancellationReminderPublication {
    public static final int REMINDER_LEAD_DAYS = 7;

    private final ContractDeadlineQuery contracts;
    private final TaskRepository tasks;
    private final CancellationReminderScheduler reminders;
    private final MemberDirectory members;
    private final PublishNotice notices;
    private final PlanningEvents events;
    private final HouseholdAccess access;
    private final Clock clock;

    public CancellationDeadlineService(ContractDeadlineQuery contracts, TaskRepository tasks,
            CancellationReminderScheduler reminders, MemberDirectory members, PublishNotice notices,
            PlanningEvents events, HouseholdAccess access, Clock clock) {
        this.contracts = contracts;
        this.tasks = tasks;
        this.reminders = reminders;
        this.members = members;
        this.notices = notices;
        this.events = events;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public void reconcile(Actor actor, ContractId contractId) {
        access.requireMember(actor);
        if (contracts.wasPurged(actor, contractId)) {
            forgetDeletedContract(actor, contractId);
            return;
        }
        LocalDate today = LocalDate.now(clock);
        ContractDeadlineView deadline = contracts.confirmedDeadline(actor, contractId, today).orElse(null);
        if (deadline == null || !"ACTIVE".equals(deadline.status())) {
            supersedeOpenOccurrences(contractId);
            return;
        }

        CancellationReview review = new CancellationReview(contractId, deadline.name(), deadline.contractEnd(),
                deadline.cancellationDeadline(), deadline.cancellationDeadline().minusDays(REMINDER_LEAD_DAYS),
                deadline.contractVersion());
        TaskSource source = deadline.sourceDocumentId() == null ? null : new TaskSource(deadline.sourceDocumentId());
        Task current = null;
        for (Task task : tasks.findCancellationReviews(contractId)) {
            CancellationReview existing = task.cancellationReview().orElseThrow();
            if (existing.cancellationDeadline().equals(review.cancellationDeadline())) {
                current = task;
            } else {
                cancelIfOpen(task);
            }
        }
        if (current == null) {
            if (tasks.wasPurged(taskId(contractId, review.cancellationDeadline()))) {
                // A member deleted this occurrence permanently; it does not come back under its stable id.
                return;
            }
            current = Task.createCancellationReview(taskId(contractId, review.cancellationDeadline()),
                    deadline.name(), deadline.cancellationDeadline(), source, review, actor.memberId(),
                    clock.instant());
            tasks.save(current);
        } else {
            boolean changed = false;
            if (!Objects.equals(current.cancellationReview().orElseThrow(), review)
                    || !Objects.equals(current.source().orElse(null), source)) {
                current.reconcileCancellationReview(review, source, clock.instant());
                changed = true;
            }
            // The occurrence was superseded (its contract was trashed or resolved, or a correction was undone) and
            // its deadline is current again: the same task, with the member's edits, becomes open work again.
            changed |= current.reviveAutomatic(clock.instant());
            if (changed) {
                tasks.save(current);
            }
        }
        // A task a member moved to the trash is not open work, so it gets no reminder until it is restored.
        if (current.isOpen()) {
            reminders.schedule(current.id(), actor.memberId(), reminderInstant(review.reminderDate()));
        }
    }

    @Override
    public void publish(Actor actor, TaskId taskId) {
        access.requireMember(actor);
        Task task = tasks.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        CancellationReview review = task.cancellationReview().orElse(null);
        LocalDate today = LocalDate.now(clock);
        if (!task.isOpen() || review == null || today.isBefore(review.reminderDate())) {
            return;
        }
        String dedupKey = "contract-cancellation:" + task.id();
        String contractEnd = review.contractEnd() == null ? "" : review.contractEnd().toString();
        String remainingDays = Long.toString(ChronoUnit.DAYS.between(today, review.cancellationDeadline()));
        for (var member : members.listMembers(actor)) {
            if (member.active()) {
                notices.publish(new NoticeDraft(member.id(), "CONTRACT_CANCELLATION_REMINDER", dedupKey,
                        Map.of("contractName", review.contractName(), "contractEnd", contractEnd,
                                "cancellationDeadline", review.cancellationDeadline().toString(),
                                "remainingDays", remainingDays),
                        "TASK", task.id().toString()));
            }
        }
    }

    private void supersedeOpenOccurrences(ContractId contractId) {
        tasks.findCancellationReviews(contractId).forEach(this::cancelIfOpen);
    }

    private void cancelIfOpen(Task task) {
        // Also supersedes an occurrence in the trash, so restoring it later does not revive work for a resolved
        // contract.
        if (task.status() == TaskStatus.OPEN) {
            task.cancelAutomatic(clock.instant());
            tasks.save(task);
        }
    }

    /**
     * The contract was deleted permanently (ADR-0021): a review a member completed stays as history and shows that
     * the contract is gone; every other occurrence was only automatic work for it and is deleted with it, including
     * one in the trash.
     */
    private void forgetDeletedContract(Actor actor, ContractId contractId) {
        for (Task task : tasks.findCancellationReviews(contractId)) {
            if (task.status() == TaskStatus.DONE) {
                if (task.markContractDeleted(clock.instant())) {
                    tasks.save(task);
                }
            } else {
                tasks.purge(task, actor.memberId(), clock.instant());
                events.taskPurged(task.id());
            }
        }
    }

    private Instant reminderInstant(LocalDate reminderDate) {
        Instant scheduled = reminderDate.atStartOfDay(clock.getZone()).toInstant();
        return scheduled.isBefore(clock.instant()) ? clock.instant() : scheduled;
    }

    static TaskId taskId(ContractId contractId, LocalDate deadline) {
        String key = "contract-cancellation:" + contractId + ":" + deadline;
        return new TaskId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }
}
