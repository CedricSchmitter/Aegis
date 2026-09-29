package dev.aegis.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.shared.MemberId;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkloadModelChoiceTest {
    private static final MemberId LISA = MemberId.random();
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Test
    void aChangeNeedsTheCurrentVersionAndRecordsWhoChangedItWhen() {
        WorkloadModelChoice choice = WorkloadModelChoice.defaultOf(Workload.DOCUMENT_CLASSIFICATION,
                ModelFamily.SONNET);

        assertThat(choice.choose(ModelFamily.OPUS, 0, LISA, NOW)).isTrue();

        assertThat(choice.family()).isEqualTo(ModelFamily.OPUS);
        assertThat(choice.version()).isEqualTo(1);
        assertThat(choice.changedBy()).contains(LISA);
        assertThat(choice.changedAt()).contains(NOW);
        assertThatThrownBy(() -> choice.choose(ModelFamily.HAIKU, 0, LISA, NOW))
                .isInstanceOf(StaleVersionException.class);
        assertThat(choice.family()).isEqualTo(ModelFamily.OPUS);
    }

    @Test
    void choosingTheSameFamilyChangesNothing() {
        WorkloadModelChoice choice = WorkloadModelChoice.defaultOf(Workload.CHAT, ModelFamily.SONNET);

        assertThat(choice.choose(ModelFamily.SONNET, 0, LISA, NOW)).isFalse();

        assertThat(choice.version()).isZero();
        assertThat(choice.changedBy()).isEmpty();
    }

    @Test
    void theConnectionCheckHasNoModelOfItsOwn() {
        assertThatThrownBy(() -> WorkloadModelChoice.defaultOf(Workload.CONNECTION_CHECK, ModelFamily.SONNET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void familiesAreRecognizedByKeyAndByModelIdentifier() {
        assertThat(ModelFamily.fromKey(" Opus")).isEqualTo(ModelFamily.OPUS);
        assertThat(ModelFamily.fromKey("HAIKU")).isEqualTo(ModelFamily.HAIKU);
        assertThatThrownBy(() -> ModelFamily.fromKey("fable")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelFamily.ofModel("claude-sonnet-5-5")).contains(ModelFamily.SONNET);
        assertThat(ModelFamily.ofModel("claude-haiku-4-5")).contains(ModelFamily.HAIKU);
        assertThat(ModelFamily.ofModel("claude-opus")).contains(ModelFamily.OPUS);
        assertThat(ModelFamily.ofModel("claude-sonnetx-1")).isEmpty();
        assertThat(ModelFamily.ofModel("claude-fable-5-1")).isEmpty();
        assertThat(ModelFamily.ofModel(null)).isEmpty();
    }
}
