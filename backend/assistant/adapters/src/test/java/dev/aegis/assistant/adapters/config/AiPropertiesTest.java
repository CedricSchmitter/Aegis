package dev.aegis.assistant.adapters.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.AiSettings;
import dev.aegis.assistant.application.ModelCatalog;
import dev.aegis.assistant.application.WorkloadProfile;
import dev.aegis.assistant.domain.ModelFamily;
import dev.aegis.assistant.domain.Workload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Model catalog and provider preference as configured under {@code aegis.ai}. */
class AiPropertiesTest {
    private static final AiProperties.Price PRICE = new AiProperties.Price(new BigDecimal("2.00"),
            new BigDecimal("10.00"), null, null);
    private static final Map<String, AiProperties.Price> PRICES = Map.of("claude-sonnet-5-5", PRICE,
            "claude-haiku-4-5", PRICE);
    private static final Map<String, AiProperties.CatalogModel> CATALOG = Map.of(
            "sonnet", new AiProperties.CatalogModel("claude-sonnet-5-5", null),
            "haiku", new AiProperties.CatalogModel(" claude-haiku-4-5", false));

    private static AiProperties.Profile profile(String provider, List<String> providers, String family) {
        return new AiProperties.Profile(provider, providers, family, new BigDecimal("0.05"), Duration.ofMinutes(2), 4,
                0, 2048, 3, 1, null);
    }

    private static AiSettings settings(Map<String, AiProperties.Price> prices,
            Map<String, AiProperties.CatalogModel> catalog, AiProperties.Profile classification) {
        return new AiProperties(null, null, null, null, prices, catalog, Map.of("classification", classification),
                null).toSettings(ZoneId.of("Europe/Berlin"));
    }

    private static WorkloadProfile classification(String provider, List<String> providers) {
        return settings(PRICES, CATALOG, profile(provider, providers, "sonnet"))
                .profile(Workload.DOCUMENT_CLASSIFICATION).orElseThrow();
    }

    @Test
    void providersKeepTheirOrderOfPreference() {
        assertThat(classification(null, List.of(" anthropic", "claude-code", "anthropic")).providers())
                .containsExactly("anthropic", "claude-code");
    }

    @Test
    void aSingleProviderIsShorthandAndTheApiIsTheDefault() {
        assertThat(classification("claude-code", null).providers()).containsExactly("claude-code");
        assertThat(classification(null, null).providers()).containsExactly("anthropic");
    }

    @Test
    void settingBothFormsIsRejected() {
        assertThatThrownBy(() -> classification("anthropic", List.of("claude-code")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("providers");
    }

    @Test
    void theCatalogNamesTheApiModelOfEachFamilyAndWhetherItAcceptsAnEffort() {
        AiSettings settings = settings(PRICES, CATALOG, profile(null, null, "sonnet"));

        assertThat(settings.profile(Workload.DOCUMENT_CLASSIFICATION).orElseThrow().family())
                .isEqualTo(ModelFamily.SONNET);
        assertThat(settings.catalog().offered()).containsExactly(
                new ModelCatalog.Entry(ModelFamily.SONNET, "claude-sonnet-5-5", true),
                new ModelCatalog.Entry(ModelFamily.HAIKU, "claude-haiku-4-5", false));
        assertThat(settings.catalog().acceptsEffort("claude-haiku-4-5")).isFalse();
        assertThat(settings.catalog().acceptsEffort("claude-sonnet-5")).isTrue();
    }

    @Test
    void aCatalogModelWithoutAPriceIsRejectedAtStartup() {
        assertThatThrownBy(() -> settings(Map.of("claude-sonnet-5-5", PRICE), CATALOG, profile(null, null, "sonnet")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claude-haiku-4-5");
    }

    @Test
    void aCatalogModelMustBelongToItsFamily() {
        assertThatThrownBy(() -> settings(PRICES,
                Map.of("opus", new AiProperties.CatalogModel("claude-sonnet-5-5", null)),
                profile(null, null, "opus")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("opus");
    }

    @Test
    void aWorkloadNeedsADefaultFamilyFromTheCatalog() {
        assertThatThrownBy(() -> settings(PRICES, CATALOG, profile(null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("family");
        assertThatThrownBy(() -> settings(PRICES, CATALOG, profile(null, null, "opus")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("catalog");
    }
}
