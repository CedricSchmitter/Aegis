package dev.aegis.assistant.application.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.shared.ProductLocale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class ChatGuardTextsTest {
    @Test
    void bothCatalogsHoldTheSameNonEmptyTextsInTheirLanguage() {
        ResourceBundle de = ChatGuardTexts.bundle(ProductLocale.DE);
        ResourceBundle en = ChatGuardTexts.bundle(ProductLocale.EN);
        assertThat(de.keySet()).isEqualTo(en.keySet())
                .contains(ChatGuardTexts.INCOMPLETE_SEARCH, ChatGuardTexts.NOT_ESTABLISHED);
        for (String key : de.keySet()) {
            assertThat(de.getString(key)).isNotBlank().isNotEqualTo(en.getString(key));
        }
        assertThat(ChatGuardTexts.text(ProductLocale.DE, ChatGuardTexts.INCOMPLETE_SEARCH)).contains("unvollständig");
        assertThat(ChatGuardTexts.text(ProductLocale.EN, ChatGuardTexts.INCOMPLETE_SEARCH)).contains("incomplete");
    }
}
