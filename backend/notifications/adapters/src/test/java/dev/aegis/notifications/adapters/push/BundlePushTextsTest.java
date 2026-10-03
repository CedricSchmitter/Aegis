package dev.aegis.notifications.adapters.push;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.notifications.domain.push.PushCategory;
import dev.aegis.shared.ProductLocale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BundlePushTextsTest {
    private final BundlePushTexts texts = new BundlePushTexts();

    @Test
    void bothCatalogsHaveTheSameKeysAndPlaceholders() {
        ResourceBundle de = BundlePushTexts.bundle(ProductLocale.DE);
        ResourceBundle en = BundlePushTexts.bundle(ProductLocale.EN);

        assertThat(de.keySet()).isEqualTo(en.keySet());
        for (String key : de.keySet()) {
            assertThat(placeholders(de.getString(key))).as(key).isEqualTo(placeholders(en.getString(key)));
            if (!key.equals("title")) {
                assertThat(de.getString(key)).as(key).isNotEqualTo(en.getString(key));
            }
        }
    }

    @Test
    void everyPushedKindHasAGenericTextWithoutParameters() {
        for (ProductLocale locale : ProductLocale.values()) {
            ResourceBundle bundle = BundlePushTexts.bundle(locale);
            for (NoticeKind kind : NoticeKind.values()) {
                if (PushCategory.of(kind).isPresent()) {
                    assertThat(placeholders(bundle.getString(kind + ".generic"))).as(locale + " " + kind).isEmpty();
                    assertThat(bundle.containsKey(kind + ".details")).as(locale + " " + kind).isTrue();
                }
            }
        }
    }

    @Test
    void detailsFillParametersAndFormatDatesForTheLocale() {
        Map<String, String> params = Map.of("contractName", "Strom", "cancellationDeadline", "2026-11-30");

        assertThat(texts.notice(ProductLocale.DE, NoticeKind.CONTRACT_CANCELLATION_REMINDER, params, true).body())
                .isEqualTo("Kündigung für „Strom“ bis 30.11.2026 prüfen.");
        assertThat(texts.notice(ProductLocale.EN, NoticeKind.CONTRACT_CANCELLATION_REMINDER, params, true).body())
                .isEqualTo("Review cancellation for “Strom” by Nov 30, 2026.");
        assertThat(texts.notice(ProductLocale.DE, NoticeKind.CONTRACT_CANCELLATION_REMINDER, params, false).body())
                .doesNotContain("Strom");
        assertThat(texts.notice(ProductLocale.EN, NoticeKind.TASK_ASSIGNED, Map.of(), true).body())
                .isEqualTo("You were assigned the task “”.");
        assertThat(texts.summary(ProductLocale.DE, 3).body()).isEqualTo("3 neue Meldungen im Eingang.");
        assertThat(texts.test(ProductLocale.EN).title()).isEqualTo("Aegis");
    }

    private static Set<String> placeholders(String text) {
        Matcher matcher = Pattern.compile("\\{(\\w+)}").matcher(text);
        return matcher.results().map(result -> result.group(1)).collect(Collectors.toSet());
    }
}
