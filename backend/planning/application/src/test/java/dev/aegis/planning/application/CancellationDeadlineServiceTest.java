package dev.aegis.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractDeadlineQuery.ContractDeadlineView;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.planning.domain.Task;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.planning.domain.TodayPolicy;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CancellationDeadlineServiceTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private final MemberId anna = MemberId.random();
    private final MemberId ben = MemberId.random();
    private final Actor actor = new Actor(anna);
    private final ContractId contractId = ContractId.random();
    private final DocumentId documentId = DocumentId.random();
    private final PlanningTestSupport.InMemoryTaskRepository tasks =
            new PlanningTestSupport.InMemoryTaskRepository();
    private final MutableClock clock = new MutableClock("2027-11-22T12:00:00Z");
    private final RecordingReminders reminders = new RecordingReminders();
    private final DeduplicatingNotices notices = new DeduplicatingNotices();
    private ContractDeadlineView view;
    private CancellationDeadlineService service;

    @BeforeEach
    void setUp() {
        view = deadline(LocalDate.of(2027, 11, 30), "ACTIVE", 3);
        ContractDeadlineQuery contracts = new ContractDeadlineQuery() {
            @Override
            public List<ContractDeadlineView> confirmedDeadlines(Actor requester, LocalDate on) {
                return view == null ? List.of() : List.of(view);
            }

            @Override
            public Optional<ContractDeadlineView> confirmedDeadline(Actor requester, ContractId id, LocalDate on) {
                return view == null || !contractId.equals(id) ? Optional.empty() : Optional.of(view);
            }
        };
        MemberDirectory members = new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor requester) {
                return List.of(new MemberSummary(anna, "anna", "Anna", ProductLocale.DE, true),
                        new MemberSummary(ben, "ben", "Ben", ProductLocale.EN, true));
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return listMembers(actor).stream().filter(member -> member.id().equals(id)).findFirst();
            }
        };
        service = new CancellationDeadlineService(contracts, tasks, reminders, members, notices,
                new PlanningTestSupport.Members(anna, ben), clock);
    }

    @Test
    void createsTheOwnerExampleAndUsesTheExistingSevenDayTodayPolicy() {
        service.reconcile(actor, contractId);

        Task task = tasks.findCancellationReviews(contractId).getFirst();
        assertThat(task.title()).isEqualTo("Fitnessstudio");
        assertThat(task.dueDate()).contains(LocalDate.of(2027, 11, 30));
        assertThat(task.plannedDate()).isEmpty();
        assertThat(task.owner()).isEmpty();
        assertThat(task.source().orElseThrow().documentId()).isEqualTo(documentId);
        assertThat(task.cancellationReview().orElseThrow().contractEnd())
                .isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(reminders.scheduledAt).singleElement()
                .isEqualTo(Instant.parse("2027-11-22T23:00:00Z"));

        assertThat(TodayPolicy.build(tasks.findOpen(), LocalDate.of(2027, 11, 22)).upcoming()).isEmpty();
        assertThat(TodayPolicy.build(tasks.findOpen(), LocalDate.of(2027, 11, 23)).upcoming())
                .extracting(Task::id).containsExactly(task.id());
        assertThat(TodayPolicy.build(tasks.findOpen(), LocalDate.of(2027, 11, 30)).dueToday())
                .extracting(Task::id).containsExactly(task.id());
        assertThat(TodayPolicy.build(tasks.findOpen(), LocalDate.of(2027, 12, 1)).overdue())
                .extracting(Task::id).containsExactly(task.id());
    }

    @Test
    void catchesUpPublishesOncePerActiveMemberAndCompletionSuppressesTheReminder() {
        clock.set("2027-12-02T10:00:00Z");
        service.reconcile(actor, contractId);
        Task task = tasks.findCancellationReviews(contractId).getFirst();
        assertThat(reminders.scheduledAt).containsExactly(clock.instant());

        service.publish(actor, task.id());
        service.publish(actor, task.id());
        assertThat(notices.drafts).hasSize(2).extracting(NoticeDraft::recipient)
                .containsExactlyInAnyOrder(anna, ben);
        assertThat(notices.drafts).allSatisfy(draft -> {
            assertThat(draft.kind()).isEqualTo("CONTRACT_CANCELLATION_REMINDER");
            assertThat(draft.params()).containsEntry("remainingDays", "-2");
        });

        Task persisted = tasks.findById(task.id()).orElseThrow();
        persisted.complete(anna, clock.instant());
        tasks.save(persisted);
        notices.clear();
        service.publish(actor, task.id());
        assertThat(notices.drafts).isEmpty();
    }

    @Test
    void correctedAndResolvedContractsSupersedeOnlyOpenWorkAndNeverRecreateCompletedOccurrences() {
        service.reconcile(actor, contractId);
        Task first = tasks.findCancellationReviews(contractId).getFirst();
        first.edit("Call the studio", "Bring membership number", clock.instant());
        first.planWork(LocalDate.of(2027, 11, 24), clock.instant());
        tasks.save(first);

        view = deadline(LocalDate.of(2027, 12, 15), "ACTIVE", 4);
        service.reconcile(actor, contractId);
        List<Task> occurrences = tasks.findCancellationReviews(contractId);
        assertThat(occurrences).hasSize(2);
        Task superseded = occurrences.stream().filter(t -> t.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(superseded.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(superseded.title()).isEqualTo("Call the studio");
        assertThat(superseded.plannedDate()).contains(LocalDate.of(2027, 11, 24));

        Task current = occurrences.stream().filter(Task::isOpen).findFirst().orElseThrow();
        current.complete(anna, clock.instant());
        tasks.save(current);
        service.reconcile(actor, contractId);
        assertThat(tasks.findCancellationReviews(contractId)).hasSize(2)
                .filteredOn(t -> t.cancellationReview().orElseThrow().cancellationDeadline()
                        .equals(LocalDate.of(2027, 12, 15)))
                .singleElement().extracting(Task::status).isEqualTo(TaskStatus.DONE);

        view = deadline(LocalDate.of(2027, 12, 15), "CANCELLED", 5);
        service.reconcile(actor, contractId);
        assertThat(tasks.findCancellationReviews(contractId)).noneMatch(Task::isOpen);
    }

    @Test
    void sameOccurrenceRefreshesContractDataWithoutOverwritingAMemberTitle() {
        service.reconcile(actor, contractId);
        Task task = tasks.findCancellationReviews(contractId).getFirst();
        task.edit("Call my contact", "", clock.instant());
        tasks.save(task);

        view = deadline(LocalDate.of(2027, 11, 30), "ACTIVE", 4, "Fitnessstudio Berlin");
        service.reconcile(actor, contractId);

        Task refreshed = tasks.findCancellationReviews(contractId).getFirst();
        assertThat(refreshed.title()).isEqualTo("Call my contact");
        assertThat(refreshed.cancellationReview().orElseThrow().contractName())
                .isEqualTo("Fitnessstudio Berlin");
    }

    @Test
    void aTrashedContractSupersedesItsReviewAndRestoringReopensTheSameTaskWithTheMemberEdits() {
        service.reconcile(actor, contractId);
        Task task = tasks.findCancellationReviews(contractId).getFirst();
        task.assign(ben, clock.instant());
        task.edit("Call the studio", "Membership 4711", clock.instant());
        tasks.save(task);

        // The contracts context publishes no deadline for a contract in the trash.
        ContractDeadlineView beforeTrash = view;
        view = null;
        service.reconcile(actor, contractId);
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(tasks.findOpen()).isEmpty();

        view = beforeTrash;
        reminders.scheduledAt.clear();
        service.reconcile(actor, contractId);

        Task reopened = tasks.findById(task.id()).orElseThrow();
        assertThat(tasks.findCancellationReviews(contractId)).hasSize(1);
        assertThat(reopened.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(reopened.owner()).contains(ben);
        assertThat(reopened.title()).isEqualTo("Call the studio");
        assertThat(reopened.note()).isEqualTo("Membership 4711");
        assertThat(reminders.scheduledAt).hasSize(1);
    }

    @Test
    void undoingACorrectionReopensTheFirstOccurrenceInsteadOfLeavingNoOpenReview() {
        service.reconcile(actor, contractId);
        ContractDeadlineView original = view;
        view = deadline(LocalDate.of(2027, 12, 15), "ACTIVE", 4);
        service.reconcile(actor, contractId);

        view = deadline(original.cancellationDeadline(), "ACTIVE", 5);
        service.reconcile(actor, contractId);

        assertThat(tasks.findOpen()).singleElement().satisfies(open -> assertThat(open.dueDate())
                .contains(original.cancellationDeadline()));
        assertThat(tasks.findCancellationReviews(contractId)).hasSize(2);
    }

    @Test
    void aReviewTaskAMemberTrashedGetsNoReminderAndIsNotRecreated() {
        service.reconcile(actor, contractId);
        Task task = tasks.findCancellationReviews(contractId).getFirst();
        task.moveToTrash(anna, clock.instant(), dev.aegis.shared.Trash.RecoveryWindow.DEFAULT);
        tasks.save(task);
        reminders.scheduledAt.clear();

        service.reconcile(actor, contractId);
        clock.set("2027-11-25T10:00:00Z");
        service.publish(actor, task.id());

        assertThat(tasks.findCancellationReviews(contractId)).singleElement()
                .satisfies(same -> assertThat(same.isTrashed()).isTrue());
        assertThat(reminders.scheduledAt).isEmpty();
        assertThat(notices.drafts).isEmpty();
    }

    @Test
    void aResolvedContractAlsoSupersedesAReviewWaitingInTheTrash() {
        service.reconcile(actor, contractId);
        Task task = tasks.findCancellationReviews(contractId).getFirst();
        task.moveToTrash(anna, clock.instant(), dev.aegis.shared.Trash.RecoveryWindow.DEFAULT);
        tasks.save(task);

        view = deadline(LocalDate.of(2027, 11, 30), "CANCELLED", 4);
        service.reconcile(actor, contractId);

        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void unknownOrUnconfirmedFactsCreateNothing() {
        view = null;
        service.reconcile(actor, contractId);
        assertThat(tasks.findAll()).isEmpty();
        assertThat(reminders.scheduledAt).isEmpty();
        assertThat(notices.drafts).isEmpty();
    }

    private ContractDeadlineView deadline(LocalDate deadline, String status, long version) {
        return deadline(deadline, status, version, "Fitnessstudio");
    }

    private ContractDeadlineView deadline(LocalDate deadline, String status, long version, String name) {
        return new ContractDeadlineView(contractId, name, "Fit GmbH", LocalDate.of(2028, 1, 1),
                deadline, "STATED_DEADLINE", List.of(UUID_FIXTURE), documentId, status, version,
                clock.instant());
    }

    private static final java.util.UUID UUID_FIXTURE = java.util.UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final class RecordingReminders implements dev.aegis.planning.application.ports.CancellationReminderScheduler {
        final List<Instant> scheduledAt = new ArrayList<>();

        @Override
        public void schedule(dev.aegis.shared.TaskId taskId, MemberId requestedBy, Instant at) {
            scheduledAt.add(at);
        }
    }

    private static final class DeduplicatingNotices implements PublishNotice {
        final List<NoticeDraft> drafts = new ArrayList<>();
        final Set<String> keys = new LinkedHashSet<>();

        @Override
        public PublishOutcome publish(NoticeDraft draft) {
            String key = draft.recipient() + ":" + draft.dedupKey();
            if (!keys.add(key)) {
                return PublishOutcome.DEDUPLICATED;
            }
            drafts.add(draft);
            return PublishOutcome.PUBLISHED;
        }

        void clear() {
            drafts.clear();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(String instant) {
            this.instant = Instant.parse(instant);
        }

        void set(String value) {
            instant = Instant.parse(value);
        }

        @Override public ZoneId getZone() { return BERLIN; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
