package dev.aegis.assistant.application;

import static dev.aegis.assistant.application.AiTestKit.AS_CEDRIC;
import static dev.aegis.assistant.application.AiTestKit.AS_LISA;
import static dev.aegis.assistant.application.ScriptedGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.api.AiOverview;
import dev.aegis.assistant.application.api.ModelOptionView;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.WorkloadView;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.RunId;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.shared.Actor;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotAuthorizedException;
import dev.aegis.shared.StaleVersionException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The household's model family per workload: shared by both members, versioned, and applied to new runs only. */
class ModelChoiceTest {
    private static final String CLASSIFICATION = Workload.DOCUMENT_CLASSIFICATION.name();
    private final AiTestKit kit = new AiTestKit();

    private static WorkloadView workload(AiOverview overview, Workload workload) {
        return overview.workloads().stream().filter(view -> view.workload().equals(workload.name())).findFirst()
                .orElseThrow();
    }

    private RunView classifyAs(Actor actor, String documentId) {
        return kit.service().request(actor, new RunRequest(CLASSIFICATION, "classification:" + documentId + ":1",
                List.of(new SourceVersion("document", documentId, 1)), Map.of("content", "Rechnung"), false));
    }

    @Test
    void theConfiguredFamilyAppliesUntilAMemberChoosesAnother() {
        AiOverview overview = kit.service().overview(AS_LISA);

        WorkloadView classification = workload(overview, Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.family()).isEqualTo("sonnet");
        assertThat(classification.model()).isEqualTo(AiTestKit.MODEL);
        assertThat(classification.choiceVersion()).isZero();
        assertThat(classification.choiceChangedBy()).isNull();
        assertThat(workload(overview, Workload.CONNECTION_CHECK).family()).isNull();
        assertThat(overview.models()).extracting(ModelOptionView::family).containsExactly("opus", "sonnet", "haiku");
        assertThat(overview.models().getFirst().model()).isEqualTo(AiTestKit.OPUS);
        assertThat(overview.models().getFirst().inputPerMillion()).isEqualByComparingTo(new BigDecimal("4"));
    }

    @Test
    void aChoiceAppliesToBothMembersFromTheNextRunWhileEarlierRunsKeepTheirModel() {
        RunView before = classifyAs(AS_LISA, "doc-1");

        AiOverview changed = kit.service().chooseModel(AS_CEDRIC, CLASSIFICATION, "opus", 0);

        WorkloadView classification = workload(changed, Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.family()).isEqualTo("opus");
        assertThat(classification.model()).isEqualTo(AiTestKit.OPUS);
        assertThat(classification.choiceVersion()).isEqualTo(1);
        assertThat(classification.choiceChangedBy()).isEqualTo(AiTestKit.CEDRIC.value());
        assertThat(classification.choiceChangedAt()).isEqualTo(AiTestKit.T0);
        assertThat(workload(changed, Workload.DOCUMENT_REVIEW).family()).isEqualTo("sonnet");
        assertThat(kit.service().get(AS_LISA, before.id()).model()).isEqualTo(AiTestKit.MODEL);
        assertThat(classifyAs(AS_LISA, "doc-2").model()).isEqualTo(AiTestKit.OPUS);
        assertThat(workload(kit.service().overview(AS_LISA), Workload.DOCUMENT_CLASSIFICATION).family())
                .isEqualTo("opus");
    }

    @Test
    void aChoiceBasedOnAnOutdatedVersionIsRejectedInsteadOfOverwritingTheOtherMember() {
        kit.service().chooseModel(AS_CEDRIC, CLASSIFICATION, "opus", 0);

        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, CLASSIFICATION, "haiku", 0))
                .isInstanceOf(StaleVersionException.class);

        WorkloadView classification = workload(kit.service().overview(AS_LISA), Workload.DOCUMENT_CLASSIFICATION);
        assertThat(classification.family()).isEqualTo("opus");
        assertThat(classification.choiceVersion()).isEqualTo(1);
        kit.service().chooseModel(AS_LISA, CLASSIFICATION, "haiku", 1);
        assertThat(kit.choices.find(Workload.DOCUMENT_CLASSIFICATION).orElseThrow().family())
                .isEqualTo(ModelFamily.HAIKU);
    }

    @Test
    void choosingTheCurrentFamilyAgainChangesNothing() {
        kit.service().chooseModel(AS_CEDRIC, CLASSIFICATION, "sonnet", 0);

        assertThat(kit.choices.find(Workload.DOCUMENT_CLASSIFICATION)).isEmpty();
    }

    @Test
    void onlyOfferedFamiliesOfWorkloadsWithTheirOwnModelCanBeChosenByMembers() {
        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, CLASSIFICATION, "claude-opus-5-5", 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.unknownFamily");
        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, CLASSIFICATION, null, 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.unknownFamily");
        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, "CONNECTION_CHECK", "opus", 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.notSelectable");
        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, "SHOPPING", "opus", 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.workload.unknown");
        assertThatThrownBy(() -> kit.service().chooseModel(new Actor(MemberId.random()), CLASSIFICATION, "opus", 0))
                .isInstanceOf(NotAuthorizedException.class);
        assertThat(kit.choices.findAll()).isEmpty();
    }

    @Test
    void aFamilyOutsideTheCatalogIsNotOffered() {
        kit.catalog.remove(ModelFamily.HAIKU);

        assertThatThrownBy(() -> kit.service().chooseModel(AS_LISA, CLASSIFICATION, "haiku", 0))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.notOffered");
        assertThat(kit.service().overview(AS_LISA).models()).extracting(ModelOptionView::family)
                .containsExactly("opus", "sonnet");
    }

    private RunView classifyWith(String family, String documentId) {
        return kit.service().request(AS_LISA, new RunRequest(CLASSIFICATION, "classification:" + documentId + ":1",
                List.of(new SourceVersion("document", documentId, 1)), Map.of("content", "Rechnung"), false, family));
    }

    @Test
    void aSingleRunCanUseAnotherOfferedFamilyWithoutChangingTheHouseholdChoice() {
        RunView opus = classifyWith("opus", "doc-1");

        assertThat(opus.model()).isEqualTo(AiTestKit.OPUS);
        assertThat(classifyWith(" ", "doc-2").model()).isEqualTo(AiTestKit.MODEL);
        assertThat(classifyAs(AS_LISA, "doc-3").model()).isEqualTo(AiTestKit.MODEL);
        assertThat(workload(kit.service().overview(AS_LISA), Workload.DOCUMENT_CLASSIFICATION).family())
                .isEqualTo("sonnet");
        assertThat(kit.choices.findAll()).isEmpty();
    }

    @Test
    void aRetryKeepsTheFamilyChosenForTheSingleRun() {
        kit.gateway.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        RunView failed = classifyWith("haiku", "doc-1");
        kit.executor().execute(new RunId(failed.id()));

        assertThat(kit.service().retry(AS_LISA, failed.id()).model()).isEqualTo(AiTestKit.HAIKU);
    }

    @Test
    void aSingleRunOnlyUsesOfferedFamilies() {
        kit.catalog.remove(ModelFamily.HAIKU);

        assertThatThrownBy(() -> classifyWith("haiku", "doc-1"))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.notOffered");
        assertThatThrownBy(() -> classifyWith("claude-opus-5-5", "doc-1"))
                .extracting(e -> ((InvalidInputException) e).code()).isEqualTo("ai.model.unknownFamily");
        assertThat(kit.runs.findLatestByKey("classification:doc-1:1")).isEmpty();
    }

    @Test
    void theEffortIsOnlySentToModelsThatAcceptIt() {
        kit.classificationEffort = "low";
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.gateway.answer(json(Map.of("label", "invoice")));
        kit.executor().execute(new RunId(classifyAs(AS_LISA, "doc-1").id()));
        kit.service().chooseModel(AS_LISA, CLASSIFICATION, "haiku", 0);

        kit.executor().execute(new RunId(classifyAs(AS_LISA, "doc-2").id()));

        assertThat(kit.gateway.requests).extracting(request -> request.model() + "/" + request.effort())
                .containsExactly(AiTestKit.MODEL + "/low", AiTestKit.HAIKU + "/null");
    }

    @Test
    void aRetryKeepsTheModelOfTheFailedRunAfterTheFamilyChanged() {
        kit.gateway.fail(FailureCategory.AUTHENTICATION, "provider.authentication", null);
        RunView failed = classifyAs(AS_LISA, "doc-1");
        kit.executor().execute(new RunId(failed.id()));
        kit.service().chooseModel(AS_CEDRIC, CLASSIFICATION, "opus", 0);

        RunView retried = kit.service().retry(AS_LISA, failed.id());

        assertThat(retried.model()).isEqualTo(AiTestKit.MODEL);
    }

    @Test
    void theConnectionCheckChecksTheChosenModels() {
        kit.service().chooseModel(AS_LISA, CLASSIFICATION, "haiku", 0);

        List<RunView> checks = kit.service().checkConnection(AS_LISA);

        assertThat(checks).extracting(RunView::model).containsExactlyInAnyOrder(AiTestKit.HAIKU, AiTestKit.MODEL);
        assertThat(kit.service().health(AS_LISA).models()).extracting(model -> model.model())
                .containsExactlyInAnyOrder(AiTestKit.HAIKU, AiTestKit.MODEL);
    }
}
