package dev.aegis.assistant.application.chat;

import dev.aegis.shared.ProductLocale;
import java.util.Locale;
import java.util.ResourceBundle;

/** Texts the chat adds to an answer itself, from the {@code chat/guard} catalogs in German and English. */
final class ChatGuardTexts {
    static final String INCOMPLETE_SEARCH = "incompleteSearch";
    static final String NOT_ESTABLISHED = "notEstablished";

    private ChatGuardTexts() {
    }

    static String text(ProductLocale locale, String key) {
        return bundle(locale).getString(key);
    }

    static ResourceBundle bundle(ProductLocale locale) {
        return ResourceBundle.getBundle("chat.guard", locale == ProductLocale.EN ? Locale.ENGLISH : Locale.GERMAN,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
