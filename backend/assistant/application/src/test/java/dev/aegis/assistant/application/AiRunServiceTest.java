package dev.aegis.assistant.application;

import static dev.aegis.assistant.application.AiTestKit.AS_CEDRIC;
import static dev.aegis.assistant.application.AiTestKit.AS_LISA;
import static dev.aegis.assistant.application.ScriptedGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.api.AiHealth;
import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.WorkloadView;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiRunServiceTest {
    private final AiTestKit kit = new AiTestKit();

    @Test
    void requestCreatesAQueuedRunAndSchedulesItsJob() {
        RunView run = kit.classify("doc-1", 3, "Rechnung", false);

        assertThat(run.state()).isEqualTo("QUEUED");
        assertThat(run.workload()).isEqualTo("DOCUMENT_CLASSIFICATION");
        assertThat(run.model()).isEqualTo(AiTestKit.MODEL);
        assertThat(run.sources()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo("doc-1");
            assertThat(source.version()).isEqualTo(3);
        });
        assertThat(kit.scheduled).containsExactly(new RunId(run.id()));
    }

    @Test
    void sameWorkIsNotQueuedTwiceAndASucceededResultIsReused() {
        RunView first = kit.classify("doc-1", 3, "Rechnung", false);
        RunView again = kit.classify("doc-1", 3, "Rechnung", false);
        assertThat(again.id()).isEqualTo(first.id());

        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.executor().execute(new RunId(first.id()));
        RunView afterSuccess = kit.classify("doc-1", 3, "Rechnung", false);
        RunView reprocessed = kit.classify("doc-1", 3, "Rechnung", true);

        assertThat(afterSuccess.id()).isEqualTo(first.id());
        assertThat(afterSuccess.state()).isEqualTo("SUCCEEDED");
        assertThat(reprocessed.id()).isNotEqualTo(first.id());
        assertThat(kit.scheduled).hasSize(2);
    }

    @Test
    void everyEntryPointRequiresHouseholdMembership() {
        Actor stranger = new Actor(MemberId.random());
        RunView run = kit.classify("doc-1", 3, "Rechnung", false);
        RunRequest request = new RunRequest("DOCUMENT_CLASSIFICATION", "x", List.of(), Map.of(), false);

        assertThatThrownBy(() -> kit.service().request(stranger, request)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().get(stranger, run.id())).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().recent(stranger, 10)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().cancel(stranger, run.id())).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().retry(stranger, run.id())).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().checkConnection(stranger))
                .isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().overview(stranger)).isInstanceOf(NotAuthorizedException.class);
        assertThatThrownBy(() -> kit.service().health(stranger)).isInstanceOf(NotAuthorizedException.class);
    }

    @Test
    void bothMembersSeeAndCancelEachOthersRuns() {
        RunView lisas = kit.classify("doc-1", 3, "Rechnung", false);

        assertThat(kit.service().recent(AS_CEDRIC, 10)).extracting(RunView::id).containsExactly(lisas.id());
        RunView cancelled = kit.service().cancel(AS_CEDRIC, lisas.id());

        assertThat(cancelled.state()).isEqualTo("CANCELLED");
        assertThat(kit.service().get(AS_LISA, lisas.id()).steps()).extracting(step -> step.kind())
                .containsExactly("CANCELLED");
    }

    @Test
    void workThatCannotRunIsRefusedInsteadOfQueued() {
        RunRequest review = new RunRequest("DOCUMENT_REVIEW", "review:1", List.of(), Map.of(), false);
        RunRequest unknown = new RunRequest("SHELL", "x", List.of(), Map.of(), false);
        RunRequest check = new RunRequest("CONNECTION_CHECK", "x", List.of(), Map.of(), false);

        assertThatThrownBy(() -> kit.service().request(AS_LISA, review)).isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.workload.unavailable");
        assertThatThrownBy(() -> kit.service().request(AS_LISA, unknown))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.workload.unknown");
        assertThatThrownBy(() -> kit.service().request(AS_LISA, check))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.workload.unavailable");
        kit.gateway.configured = false;
        assertThatThrownBy(() -> kit.classify("doc-1", 3, "Rechnung", false))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.notConfigured");
        assertThat(kit.runs.size()).isZero();
    }

    @Test
    void configuredProviderServesBothHouseholdMembers() {
        RunRequest request = new RunRequest("DOCUMENT_CLASSIFICATION", "classification:doc-7:1", List.of(),
                Map.of(), false);

        AiOverview cedrics = kit.service().overview(AS_CEDRIC);
        AiOverview lisas = kit.service().overview(AS_LISA);

        assertThat(cedrics.memberAllowed()).isTrue();
        assertThat(cedrics.workloads()).filteredOn(view -> view.workload().equals("DOCUMENT_CLASSIFICATION"))
                .singleElement().extracting(WorkloadView::available).isEqualTo(true);
        assertThat(lisas.memberAllowed()).isTrue();
        assertThat(kit.service().request(AS_CEDRIC, request).state()).isEqualTo("QUEUED");
        assertThat(kit.runs.size()).isEqualTo(1);
    }

    @Test
    void modelWithoutAPriceCannotRun() {
        kit.prices.clear();

        assertThatThrownBy(() -> kit.classify("doc-1", 3, "Rechnung", false))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.price.unknown");
    }

    @Test
    void connectionCheckStartsOneRunPerDistinctModelAndSucceedsOnTheExpectedAnswer() {
        List<RunView> checks = kit.service().checkConnection(AS_LISA);

        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.workload()).isEqualTo("CONNECTION_CHECK");
            assertThat(check.model()).isEqualTo(AiTestKit.MODEL);
        });
        kit.gateway.answer(json(Map.of("status", "ok")));
        kit.executor().execute(new RunId(checks.getFirst().id()));

        RunView done = kit.service().get(AS_LISA, checks.getFirst().id());
        assertThat(done.state()).isEqualTo("SUCCEEDED");
        assertThat(done.output()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(kit.gateway.requests.getFirst().tools()).isEmpty();
        assertThat(kit.gateway.requests.getFirst().outputSchema()).isEqualTo(ConnectionCheckHandler.OUTPUT_SCHEMA);
    }

    @Test
    void connectionCheckWithoutCredentialsIsRefused() {
        kit.gateway.configured = false;

        assertThatThrownBy(() -> kit.service().checkConnection(AS_LISA))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.notConfigured");
    }

    @Test
    void retryStartsALinkedRunOnlyAfterFailureOrCancellation() {
        RunView original = kit.classify("doc-1", 3, "Rechnung", false);
        assertThatThrownBy(() -> kit.service().retry(AS_LISA, original.id()))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.run.notRetryable");
        kit.gateway.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        kit.executor().execute(new RunId(original.id()));

        RunView retried = kit.service().retry(AS_CEDRIC, original.id());

        assertThat(retried.retryOf()).isEqualTo(original.id());
        assertThat(retried.requestedBy()).isEqualTo(AiTestKit.CEDRIC.value());
        assertThat(retried.state()).isEqualTo("QUEUED");
        assertThat(retried.sources()).isEqualTo(original.sources());
    }

    @Test
    void unknownRunIsNotFound() {
        assertThatThrownBy(() -> kit.service().get(AS_LISA, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void overviewShowsWhichWorkloadsCanRunAndTodaysSpending() {
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.executor().execute(new RunId(kit.classify("doc-1", 3, "Rechnung", false).id()));

        AiOverview overview = kit.service().overview(AS_LISA);

        assertThat(overview.configured()).isTrue();
        assertThat(overview.spentToday()).isEqualByComparingTo("0.0015");
        assertThat(overview.workloads()).extracting(WorkloadView::workload, WorkloadView::available)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CONNECTION_CHECK", true),
                        org.assertj.core.groups.Tuple.tuple("DOCUMENT_CLASSIFICATION", true),
                        org.assertj.core.groups.Tuple.tuple("DOCUMENT_REVIEW", false));
    }

    @Test
    void healthReportsTheLatestOutcomeOfEveryConfiguredModel() {
        AiHealth.ModelHealth unverified = new AiHealth.ModelHealth(ScriptedGateway.PROVIDER, AiTestKit.MODEL, null,
                null, null);
        assertThat(kit.service().health(AS_LISA)).isEqualTo(new AiHealth(true, null, List.of(unverified)));
        kit.gateway.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        kit.executor().execute(new RunId(kit.classify("doc-1", 3, "Rechnung", false).id()));

        AiHealth failed = kit.service().health(AS_LISA);
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.clock.advance(java.time.Duration.ofMinutes(1));
        kit.executor().execute(new RunId(kit.classify("doc-2", 1, "Rechnung", false).id()));
        AiHealth recovered = kit.service().health(AS_LISA);

        assertThat(failed.models()).singleElement().satisfies(model -> {
            assertThat(model.lastState()).isEqualTo("FAILED");
            assertThat(model.lastFailureCategory()).isEqualTo("AUTHENTICATION");
        });
        assertThat(failed.lastSuccessAt()).isNull();
        assertThat(recovered.models()).singleElement().satisfies(model -> {
            assertThat(model.lastState()).isEqualTo("SUCCEEDED");
            assertThat(model.lastFailureCategory()).isNull();
        });
        assertThat(recovered.lastSuccessAt()).isNotNull();
    }

    @Test
    void workloadNamesAreStableCodes() {
        assertThat(Workload.fromConfigKey("classification")).isEqualTo(Workload.DOCUMENT_CLASSIFICATION);
        assertThat(Workload.fromConfigKey(" Connection-Check ")).isEqualTo(Workload.CONNECTION_CHECK);
        assertThatThrownBy(() -> Workload.fromConfigKey("shell")).isInstanceOf(IllegalArgumentException.class);
    }
}
