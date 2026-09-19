package dev.aegis.assistant.adapters.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.domain.ModelSelection;
import dev.aegis.assistant.domain.Workload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Provider preference of the workload profiles as configured under {@code aegis.ai.workloads}. */
class AiPropertiesTest {
    private static List<ModelSelection> models(String provider, List<String> providers) {
        AiProperties.Profile profile = new AiProperties.Profile(provider, providers, "claude-sonnet-5",
                new BigDecimal("0.05"), Duration.ofMinutes(2), 4, 0, 2048, 3, 1, null);
        return new AiProperties(null, null, null, null, Map.of(), Map.of("classification", profile), null)
                .toSettings(ZoneId.of("Europe/Berlin")).profile(Workload.DOCUMENT_CLASSIFICATION).orElseThrow()
                .models();
    }

    @Test
    void providersKeepTheirOrderOfPreferenceWithTheSameModel() {
        assertThat(models(null, List.of(" anthropic", "claude-code", "anthropic"))).containsExactly(
                new ModelSelection("anthropic", "claude-sonnet-5"),
                new ModelSelection("claude-code", "claude-sonnet-5"));
    }

    @Test
    void aSingleProviderIsShorthandAndTheApiIsTheDefault() {
        assertThat(models("claude-code", null)).containsExactly(new ModelSelection("claude-code", "claude-sonnet-5"));
        assertThat(models(null, null)).containsExactly(new ModelSelection("anthropic", "claude-sonnet-5"));
    }

    @Test
    void settingBothFormsIsRejected() {
        assertThatThrownBy(() -> models("anthropic", List.of("claude-code")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("providers");
    }
}
