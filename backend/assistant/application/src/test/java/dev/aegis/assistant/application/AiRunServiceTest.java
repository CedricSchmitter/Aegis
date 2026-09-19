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

    /** Regression for issue #59: a key names one unit of work and cannot be reused for different work. */
    @Test
    void aReusedKeyForDifferentWorkIsRejectedWhileActiveAndAfterSuccess() {
        RunView active = kit.classify("doc-1", 3, "Rechnung", false);
        RunRequest otherVersion = new RunRequest("DOCUMENT_CLASSIFICATION", "classification:doc-1:3",
                List.of(new dev.aegis.assistant.application.api.SourceVersion("document", "doc-1", 4)),
                Map.of("content", "Rechnung"), false);
        RunRequest otherParameters = new RunRequest("DOCUMENT_CLASSIFICATION", "classification:doc-1:3",
                List.of(new dev.aegis.assistant.application.api.SourceVersion("document", "doc-1", 3)),
                Map.of("content", "Police"), false);

        for (RunRequest conflicting : List.of(otherVersion, otherParameters)) {
            assertThatThrownBy(() -> kit.service().request(AS_LISA, conflicting))
                    .isInstanceOf(InvalidInputException.class)
                    .hasFieldOrPropertyWithValue("code", "ai.run.keyConflict");
        }
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.executor().execute(new RunId(active.id()));
        assertThatThrownBy(() -> kit.service().request(AS_LISA, otherVersion)).isInstanceOf(
                InvalidInputException.class).hasFieldOrPropertyWithValue("code", "ai.run.keyConflict");
        assertThatThrownBy(() -> kit.service().request(AS_LISA, new RunRequest(otherVersion.workload(),
                otherVersion.idempotencyKey(), otherVersion.sources(), otherVersion.parameters(), true)))
                .as("reprocessing does not make a key reusable for other work")
                .hasFieldOrPropertyWithValue("code", "ai.run.keyConflict");
        assertThat(kit.service().get(AS_LISA, active.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(kit.scheduled).hasSize(1);
    }

    @Test
    void bothMembersRequestingTheSameWorkShareOneRun() {
        RunView lisas = kit.classify("doc-1", 3, "Rechnung", false);
        RunView cedrics = kit.service().request(AS_CEDRIC, new RunRequest("DOCUMENT_CLASSIFICATION",
                "classification:doc-1:3", List.of(new dev.aegis.assistant.application.api.SourceVersion("document",
                        "doc-1", 3)), Map.of("content", "Rechnung"), false));

        assertThat(cedrics.id()).isEqualTo(lisas.id());
        assertThat(kit.scheduled).hasSize(1);
    }

    @Test
    void tryRequestReportsUnavailabilityInsteadOfThrowing() {
        RunRequest request = new RunRequest("DOCUMENT_CLASSIFICATION", "classification:doc-9:1",
                List.of(new dev.aegis.assistant.application.api.SourceVersion("document", "doc-9", 1)), Map.of(),
                false);

        assertThat(kit.service().tryRequest(new Actor(MemberId.random()), request))
                .isEqualTo(new dev.aegis.assistant.application.api.RequestOutcome.Refused("auth.forbidden"));
        kit.gateway.configured = false;
        assertThat(kit.service().tryRequest(AS_LISA, request))
                .isEqualTo(new dev.aegis.assistant.application.api.RequestOutcome.Refused("ai.notConfigured"));
        kit.gateway.configured = true;
        assertThat(kit.service().tryRequest(AS_LISA, request))
                .isInstanceOf(dev.aegis.assistant.application.api.RequestOutcome.Requested.class);
        assertThat(kit.service().latestByKeys(List.of("classification:doc-9:1", "unknown")))
                .containsOnlyKeys("classification:doc-9:1");
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
    // Provider preference ---------------------------------------------------------------------------------------------

    private RunView classifyAs(Actor actor, String documentId) {
        return kit.service().request(actor, new RunRequest("DOCUMENT_CLASSIFICATION", "classification:" + documentId,
                List.of(new dev.aegis.assistant.application.api.SourceVersion("document", documentId, 1)),
                Map.of("content", "Rechnung"), false));
    }

    @Test
    void thePreferredProviderIsUsedWhileItHasCredentials() {
        kit.subscription.configured = true;

        assertThat(classifyAs(AS_CEDRIC, "doc-1").provider()).isEqualTo(ScriptedGateway.PROVIDER);
    }

    @Test
    void withoutThePreferredCredentialsTheSubscriptionServesOnlyItsOwner() {
        kit.gateway.configured = false;
        kit.subscription.configured = true;
        RunRequest lisas = new RunRequest("DOCUMENT_CLASSIFICATION", "classification:doc-2",
                List.of(new dev.aegis.assistant.application.api.SourceVersion("document", "doc-2", 1)), Map.of(),
                false);

        assertThat(classifyAs(AS_CEDRIC, "doc-1").provider()).isEqualTo(AiTestKit.SUBSCRIPTION);
        assertThatThrownBy(() -> kit.service().request(AS_LISA, lisas)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "ai.provider.memberNotAllowed");
        assertThat(kit.service().tryRequest(AS_LISA, lisas)).isEqualTo(
                new dev.aegis.assistant.application.api.RequestOutcome.Refused("ai.provider.memberNotAllowed"));
        assertThat(kit.scheduled).hasSize(1);
    }

    @Test
    void theSubscriptionReceivesTheSameCallAsThePreferredProvider() {
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.executor().execute(new RunId(classifyAs(AS_CEDRIC, "doc-1").id()));
        kit.gateway.configured = false;
        kit.subscription.configured = true;
        kit.subscription.answer(json(Map.of("label", "invoice")));

        RunView run = classifyAs(AS_CEDRIC, "doc-2");
        kit.executor().execute(new RunId(run.id()));

        assertThat(kit.service().get(AS_CEDRIC, run.id()).state()).isEqualTo("SUCCEEDED");
        var viaKey = kit.gateway.requests.getFirst();
        var viaSubscription = kit.subscription.requests.getFirst();
        assertThat(viaSubscription.model()).isEqualTo(viaKey.model());
        assertThat(viaSubscription.system()).isEqualTo(viaKey.system());
        assertThat(viaSubscription.outputSchema()).isEqualTo(viaKey.outputSchema());
        assertThat(viaSubscription.maxOutputTokens()).isEqualTo(viaKey.maxOutputTokens());
        assertThat(viaSubscription.effort()).isEqualTo(viaKey.effort());
    }

    @Test
    void aQueuedSubscriptionRunIsNotExecutedForAMemberWhoLostAccess() {
        kit.gateway.configured = false;
        kit.subscription.configured = true;
        RunView run = classifyAs(AS_CEDRIC, "doc-1");
        kit.subscription.allowed = java.util.Set.of();

        kit.executor().execute(new RunId(run.id()));

        RunView failed = kit.service().get(AS_CEDRIC, run.id());
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.failureCategory()).isEqualTo(FailureCategory.NOT_AUTHORIZED.name());
        assertThat(kit.subscription.requests).isEmpty();
    }

    @Test
    void aRetryChoosesTheProviderAgainByTheCurrentPreference() {
        kit.gateway.configured = false;
        kit.subscription.configured = true;
        kit.subscription.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        RunView failed = classifyAs(AS_CEDRIC, "doc-1");
        kit.executor().execute(new RunId(failed.id()));
        assertThat(kit.service().get(AS_CEDRIC, failed.id()).state()).isEqualTo("FAILED");

        kit.gateway.configured = true;
        RunView retried = kit.service().retry(AS_LISA, failed.id());

        assertThat(retried.provider()).isEqualTo(ScriptedGateway.PROVIDER);
        assertThat(retried.model()).isEqualTo(AiTestKit.MODEL);
    }

    @Test
    void statusAndConnectionCheckFollowTheProviderEachMemberWouldUse() {
        kit.gateway.configured = false;
        kit.subscription.configured = true;

        AiOverview cedrics = kit.service().overview(AS_CEDRIC);
        AiOverview lisas = kit.service().overview(AS_LISA);
        List<RunView> checks = kit.service().checkConnection(AS_CEDRIC);

        assertThat(cedrics.memberAllowed()).isTrue();
        assertThat(cedrics.limitProvider()).isEqualTo(AiTestKit.SUBSCRIPTION);
        assertThat(cedrics.dailyCostLimit()).isEqualByComparingTo("50.00");
        assertThat(cedrics.workloads()).filteredOn(view -> AiTestKit.SUBSCRIPTION.equals(view.provider()))
                .isNotEmpty().allSatisfy(view -> assertThat(view.maxCost()).isEqualByComparingTo("0.50"));
        assertThat(checks.getFirst().maxCost()).isEqualByComparingTo("0.50");
        assertThat(lisas.limitProvider()).isEqualTo(AiTestKit.SUBSCRIPTION);
        assertThat(cedrics.workloads()).filteredOn(view -> view.workload().equals("DOCUMENT_CLASSIFICATION"))
                .singleElement().satisfies(view -> {
                    assertThat(view.provider()).isEqualTo(AiTestKit.SUBSCRIPTION);
                    assertThat(view.available()).isTrue();
                });
        assertThat(lisas.configured()).isTrue();
        assertThat(lisas.memberAllowed()).isFalse();
        assertThat(lisas.workloads()).allSatisfy(view -> assertThat(view.available()).isFalse());
        assertThat(checks).singleElement().satisfies(check ->
                assertThat(check.provider()).isEqualTo(AiTestKit.SUBSCRIPTION));
        assertThatThrownBy(() -> kit.service().checkConnection(AS_LISA)).isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "ai.provider.memberNotAllowed");
        assertThat(kit.service().health(AS_LISA).models()).extracting(AiHealth.ModelHealth::provider)
                .containsOnly(AiTestKit.SUBSCRIPTION);
    }
}
