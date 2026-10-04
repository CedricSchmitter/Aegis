package dev.aegis.assistant.application;

import static dev.aegis.assistant.application.AiTestKit.AS_CEDRIC;
import static dev.aegis.assistant.application.AiTestKit.AS_LISA;
import static dev.aegis.assistant.application.ScriptedGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.WorkloadEstimate;
import dev.aegis.assistant.application.api.WorkloadView;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.AiRun;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.Money;
import dev.aegis.assistant.domain.RunFailure;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.RunState;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The household's own daily and session limits per workload: shared by both members, versioned, applied to every
 * provider, and counted apart from the shared daily limit of the workloads without own limits.
 */
class CostLimitTest {
    private static final String CLASSIFICATION = Workload.DOCUMENT_CLASSIFICATION.name();
    private final AiTestKit kit = new AiTestKit();

    private static WorkloadView workload(AiOverview overview, Workload workload) {
        return overview.workloads().stream().filter(view -> view.workload().equals(workload.name())).findFirst()
                .orElseThrow();
    }

    private static BigDecimal usd(String amount) {
        return new BigDecimal(amount);
    }

    private RunView classifyAs(Actor actor, String documentId) {
        return kit.service().request(actor, new RunRequest(CLASSIFICATION, "classification:" + documentId + ":1",
                List.of(new SourceVersion("document", documentId, 1)), Map.of("content", "Rechnung"), false));
    }

    /** An answer whose reported usage costs exactly 0.02 USD at the test prices (1 and 5 USD per million). */
    private static ModelTurn answerCostingTwoCents() {
        ModelTurn answer = json(Map.of("label", "invoice"));
        return new ModelTurn(List.of(new ContentPart.Text(answer.text())), ModelTurn.Stop.END,
                new TokenUsage(19_500, 100, 0, 0), answer.modelId(), answer.text(), answer.structuredOutput(), null);
    }

    @Test
    void theConfiguredDefaultsApplyUntilAMemberSetsLimits() {
        AiOverview overview = kit.service().overview(AS_LISA);

        WorkloadView classification = workload(overview, Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.ownLimits()).isFalse();
        assertThat(classification.limitsSelectable()).isTrue();
        assertThat(classification.limitsVersion()).isZero();
        assertThat(classification.limitsChangedBy()).isNull();
        assertThat(classification.maxCost()).isEqualByComparingTo("0.05");
        assertThat(classification.dailyCostLimit()).isEqualByComparingTo("2.00");
        assertThat(overview.dailyCostLimit()).isEqualByComparingTo("2.00");
        assertThat(workload(overview, Workload.CONNECTION_CHECK).limitsSelectable()).isFalse();
    }

    @Test
    void ownLimitsApplyToBothMembersAndEveryProviderFromTheNextRun() {
        RunView before = classifyAs(AS_LISA, "doc-1");

        AiOverview changed = kit.service().setCostLimits(AS_CEDRIC, CLASSIFICATION, usd("1.50"), usd("0.25"), 0);

        WorkloadView classification = workload(changed, Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.ownLimits()).isTrue();
        assertThat(classification.limitsVersion()).isEqualTo(1);
        assertThat(classification.limitsChangedBy()).isEqualTo(AiTestKit.CEDRIC.value());
        assertThat(classification.limitsChangedAt()).isEqualTo(AiTestKit.T0);
        assertThat(classification.maxCost()).isEqualByComparingTo("0.25");
        assertThat(classification.dailyCostLimit()).isEqualByComparingTo("1.50");
        assertThat(workload(changed, Workload.DOCUMENT_REVIEW).ownLimits()).isFalse();
        assertThat(workload(changed, Workload.DOCUMENT_REVIEW).maxCost()).isEqualByComparingTo("0.05");
        assertThat(kit.service().get(AS_LISA, before.id()).maxCost()).as("earlier runs keep their budget")
                .isEqualByComparingTo("0.05");
        assertThat(classifyAs(AS_LISA, "doc-2").maxCost()).isEqualByComparingTo("0.25");

        kit.gateway.configured = false;
        kit.subscription.configured = true;
        assertThat(classifyAs(AS_CEDRIC, "doc-3").maxCost())
                .as("the session limit replaces the subscription's own run budget").isEqualByComparingTo("0.25");
        assertThat(kit.service().estimate(AS_CEDRIC, CLASSIFICATION).dailyCostLimit())
                .as("and the daily limit the subscription's own daily limit").isEqualByComparingTo("1.50");
    }

    @Test
    void anOwnDailyLimitCountsOnlyTheWorkloadsCallsAndReplacesTheSharedLimit() {
        kit.dailyLimit = Money.of("0.001", "USD");
        kit.service().setCostLimits(AS_LISA, CLASSIFICATION, usd("0.02"), usd("0.02"), 0);
        kit.gateway.answer(answerCostingTwoCents()).answer(json(Map.of("label", "insurance")));
        RunId first = new RunId(classifyAs(AS_LISA, "doc-a").id());

        kit.executor().execute(first);

        assertThat(kit.runs.stored(first).state()).as("the tiny shared limit does not apply")
                .isEqualTo(RunState.SUCCEEDED);
        AiOverview overview = kit.service().overview(AS_LISA);
        assertThat(workload(overview, Workload.DOCUMENT_CLASSIFICATION).spentToday()).isEqualByComparingTo("0.02");
        assertThat(overview.spentToday()).as("the shared limit counts only workloads without own limits")
                .isEqualByComparingTo("0");

        RunId second = new RunId(classifyAs(AS_LISA, "doc-b").id());
        Instant midnight = Instant.parse("2026-09-18T22:00:00Z");
        assertThat(kit.executor().execute(second)).isEqualTo(new RunExecutor.RunAgainAt(midnight));

        AiRun waiting = kit.runs.stored(second);
        assertThat(waiting.state()).isEqualTo(RunState.WAITING_RETRY);
        assertThat(waiting.failure()).get().extracting(RunFailure::code).isEqualTo(AiRun.DAILY_LIMIT);
        WorkloadEstimate estimate = kit.service().estimate(AS_LISA, CLASSIFICATION);
        assertThat(estimate.dailyCostLimit()).isEqualByComparingTo("0.02");
        assertThat(estimate.spentToday()).isEqualByComparingTo("0.02");
        assertThat(estimate.runBudget()).isEqualByComparingTo("0.02");
    }

    @Test
    void limitsBasedOnAnOutdatedVersionAreRejectedInsteadOfOverwritingTheOtherMember() {
        kit.service().setCostLimits(AS_CEDRIC, CLASSIFICATION, usd("3"), usd("0.30"), 0);

        assertThatThrownBy(() -> kit.service().setCostLimits(AS_LISA, CLASSIFICATION, usd("1"), usd("0.10"), 0))
                .isInstanceOf(StaleVersionException.class);

        WorkloadView classification = workload(kit.service().overview(AS_LISA), Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.dailyCostLimit()).isEqualByComparingTo("3");
        kit.service().setCostLimits(AS_LISA, CLASSIFICATION, usd("1"), usd("0.10"), 1);
        assertThat(kit.limits.find(Workload.DOCUMENT_CLASSIFICATION).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void onlyValidAmountsForWorkloadsWithTheirOwnModelAreAccepted() {
        Map<String, BigDecimal[]> invalid = Map.of(
                "ai.limits.notPositive", new BigDecimal[] {usd("0"), usd("0.10")},
                "ai.limits.tooPrecise", new BigDecimal[] {usd("1.001"), usd("0.10")},
                "ai.limits.tooHigh", new BigDecimal[] {usd("1000.01"), usd("0.10")},
                "ai.limits.runAboveDaily", new BigDecimal[] {usd("0.50"), usd("0.60")});
        invalid.forEach((code, amounts) -> assertThatThrownBy(() -> kit.service().setCostLimits(AS_LISA,
                CLASSIFICATION, amounts[0], amounts[1], 0)).extracting(e -> ((InvalidInputException) e).code())
                .as(code).isEqualTo(code));
        assertThatThrownBy(() -> kit.service().setCostLimits(AS_LISA, CLASSIFICATION, null, usd("0.10"), 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.limits.notPositive");
        assertThatThrownBy(() -> kit.service().setCostLimits(AS_LISA, "CONNECTION_CHECK", usd("1"), usd("0.1"), 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.limits.notSelectable");
        assertThatThrownBy(() -> kit.service().setCostLimits(AS_LISA, "SHOPPING", usd("1"), usd("0.1"), 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.workload.unknown");
        assertThatThrownBy(() -> kit.service().setCostLimits(new Actor(MemberId.random()), CLASSIFICATION, usd("1"),
                usd("0.1"), 0)).isInstanceOf(NotAuthorizedException.class);
        assertThat(kit.limits.findAll()).isEmpty();
    }

    @Test
    void settingTheCurrentLimitsAgainChangesNothing() {
        kit.service().setCostLimits(AS_LISA, CLASSIFICATION, usd("1.00"), usd("0.10"), 0);

        AiOverview again = kit.service().setCostLimits(AS_CEDRIC, CLASSIFICATION, usd("1"), usd("0.1"), 1);

        assertThat(workload(again, Workload.DOCUMENT_CLASSIFICATION).limitsVersion()).isEqualTo(1);
        assertThat(workload(again, Workload.DOCUMENT_CLASSIFICATION).limitsChangedBy())
                .isEqualTo(AiTestKit.LISA.value());
    }

    @Test
    void aRetryUsesTheCurrentSessionLimit() {
        kit.gateway.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        RunView failed = classifyAs(AS_LISA, "doc-1");
        kit.executor().execute(new RunId(failed.id()));
        kit.service().setCostLimits(AS_LISA, CLASSIFICATION, usd("1.00"), usd("0.40"), 0);

        assertThat(kit.service().retry(AS_LISA, failed.id()).maxCost()).isEqualByComparingTo("0.40");
    }
}
