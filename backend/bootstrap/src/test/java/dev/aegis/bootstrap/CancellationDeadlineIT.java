package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractCommands.CorrectFactCommand;
import dev.aegis.contracts.application.api.ContractCommands.CreateContractCommand;
import dev.aegis.contracts.application.api.ContractCommands.EnterFactCommand;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.notifications.application.api.NoticeSummary;
import dev.aegis.planning.adapters.jobs.ContractCancellationTasks;
import dev.aegis.planning.application.api.CancellationDeadlineReconciliation;
import dev.aegis.planning.application.api.CancellationReminderPublication;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.planning.domain.TaskStatus;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof of atomic scheduling, durable deduplication and optimistic occurrence reconciliation. */
class CancellationDeadlineIT extends PostgresIT {
    @Autowired ContractCommands contracts;
    @Autowired ContractQueries contractQueries;
    @Autowired ContractRepository contractRepository;
    @Autowired TaskRepository tasks;
    @Autowired TaskCommands taskCommands;
    @Autowired CancellationDeadlineReconciliation reconciliation;
    @Autowired CancellationReminderPublication reminderPublication;
    @Autowired NoticeInbox notices;
    @Autowired MemberCredentialsQuery members;
    @Autowired SchedulerClient scheduler;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;
    @LocalServerPort int port;

    @Test
    void confirmedFactFlowsThroughHttpIntoTodayAndTheLocalizedNoticePayload() {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(ANNA, PASSWORD)).isTrue();
        String name = "HTTP Fitness " + System.nanoTime();
        var created = client.postJson("/api/contracts", "{\"name\":\"" + name + "\"}");
        assertThat(created.status()).isEqualTo(201);
        String id = client.json(created).path("id").asText();
        long version = client.json(created).path("version").asLong();
        LocalDate deadline = LocalDate.now(clock).plusDays(7);

        var entered = client.postJson("/api/contracts/" + id + "/facts",
                "{\"type\":\"CANCELLATION_DEADLINE\",\"value\":\"" + deadline
                        + "\",\"expectedVersion\":" + version + "}");
        assertThat(entered.status()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var taskList = client.get("/api/tasks");
            assertThat(taskList.status()).isEqualTo(200);
            assertThat(taskList.body()).contains(name).contains("cancellationReview").contains(deadline.toString());
            var today = client.get("/api/today");
            assertThat(today.status()).isEqualTo(200);
            assertThat(client.json(today).path("upcoming").toString()).contains(name);
            var inbox = client.get("/api/notices");
            assertThat(inbox.status()).isEqualTo(200);
            assertThat(inbox.body()).contains("CONTRACT_CANCELLATION_REMINDER").contains(name);
        });
        String taskId = tasks.findCancellationReviews(ContractId.of(id)).getFirst().id().toString();
        markReviewNoticesRead(actor(ANNA), taskId);
        markReviewNoticesRead(actor(BEN), taskId);
    }

    @Test
    void contractAndReconciliationJobRollBackTogether() {
        Actor anna = actor(ANNA);
        ContractId[] id = new ContractId[1];

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            id[0] = contracts.create(anna, new CreateContractCommand("Rollback contract", null, null)).id();
            throw new IllegalStateException("simulated failure after durable scheduling");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(contractRepository.findById(id[0])).isEmpty();
        assertThat(scheduler.getScheduledExecutionsForTask(ContractCancellationTasks.RECONCILE.getTaskName(),
                dev.aegis.planning.adapters.jobs.ContractReconciliationPayload.class))
                .noneMatch(execution -> execution.getData().contractId().equals(id[0].toString()));
    }

    @Test
    void overdueConfirmedDeadlineCreatesOneTaskAndOneNoticePerMemberAcrossRetries() {
        Actor anna = actor(ANNA);
        Actor ben = actor(BEN);
        LocalDate deadline = LocalDate.now(clock).minusDays(1);
        var contract = contracts.create(anna, new CreateContractCommand("Fitnessstudio", null, null));
        contract = contracts.enterFact(anna, contract.id(), new EnterFactCommand("CANCELLATION_DEADLINE",
                deadline.toString(), null, null, null, null, null, null), contract.version());
        ContractId id = contract.id();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(tasks.findCancellationReviews(id)).singleElement()
                        .satisfies(task -> assertThat(task.dueDate()).contains(deadline)));
        var task = tasks.findCancellationReviews(id).getFirst();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(reviewNotices(anna, task.id().toString())).singleElement();
            assertThat(reviewNotices(ben, task.id().toString())).singleElement();
        });

        String annasNotice = reviewNotices(anna, task.id().toString()).getFirst().id();
        notices.markRead(anna, annasNotice);
        reminderPublication.publish(anna, task.id());
        reminderPublication.publish(anna, task.id());
        reconciliation.reconcile(anna, id);
        reconciliation.reconcile(anna, id);

        assertThat(tasks.findCancellationReviews(id)).hasSize(1);
        assertThat(reviewNotices(anna, task.id().toString())).hasSize(1);
        assertThat(reviewNotices(anna, task.id().toString()).getFirst().readAt()).isNotNull();
        assertThat(tasks.findById(task.id()).orElseThrow().isOpen()).isTrue();

        taskCommands.complete(anna, task.id(), tasks.findById(task.id()).orElseThrow().version());
        assertThat(reviewNotices(anna, task.id().toString()).getFirst().readAt()).isNotNull();
        markReviewNoticesRead(ben, task.id().toString());
    }

    @Test
    void correctedDeadlineCreatesANewOccurrenceAndPreservesCompletion() {
        Actor anna = actor(ANNA);
        var contract = contracts.create(anna, new CreateContractCommand("Internet", null, null));
        LocalDate oldDeadline = LocalDate.now(clock).plusMonths(2);
        contract = contracts.enterFact(anna, contract.id(), new EnterFactCommand("CANCELLATION_DEADLINE",
                oldDeadline.toString(), null, null, null, null, null, null), contract.version());
        ContractId id = contract.id();
        UUID factId = contractQueries.get(anna, id).facts().stream()
                .filter(fact -> fact.type().equals("CANCELLATION_DEADLINE")).findFirst().orElseThrow().id();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(tasks.findCancellationReviews(id)).hasSize(1));

        LocalDate corrected = oldDeadline.plusDays(10);
        contract = contracts.correctFact(anna, id, factId,
                new CorrectFactCommand(corrected.toString(), null, null, "corrected"), contract.version());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(tasks.findCancellationReviews(id)).hasSize(2);
            assertThat(tasks.findCancellationReviews(id)).filteredOn(task -> task.status() == TaskStatus.CANCELLED)
                    .hasSize(1);
        });
        var current = tasks.findCancellationReviews(id).stream().filter(task -> task.status() == TaskStatus.OPEN)
                .findFirst().orElseThrow();
        taskCommands.complete(anna, current.id(), current.version());
        reconciliation.reconcile(anna, id);

        assertThat(tasks.findCancellationReviews(id)).hasSize(2)
                .filteredOn(task -> task.status() == TaskStatus.DONE).hasSize(1);
    }

    private Actor actor(String username) {
        return new Actor(members.findCredentials(username).orElseThrow().id());
    }

    private List<NoticeSummary> reviewNotices(Actor recipient, String taskId) {
        return notices.list(recipient, false).stream()
                .filter(notice -> notice.kind().equals("CONTRACT_CANCELLATION_REMINDER"))
                .filter(notice -> taskId.equals(notice.linkId()))
                .toList();
    }

    private void markReviewNoticesRead(Actor recipient, String taskId) {
        reviewNotices(recipient, taskId).stream().filter(notice -> notice.readAt() == null)
                .forEach(notice -> notices.markRead(recipient, notice.id()));
    }
}
