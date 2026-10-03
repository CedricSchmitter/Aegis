package dev.aegis.notifications.application.push;

import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.shared.ProductLocale;
import java.util.Map;

/** Localized push texts; a notification adapter renders them in the recipient's locale. */
public interface PushTexts {
    record Text(String title, String body) {
    }

    /**
     * One notice. Without details the text only names the kind of notice; with details it may include parameters
     * such as a task title.
     */
    Text notice(ProductLocale locale, NoticeKind kind, Map<String, String> params, boolean details);

    /** Several notices at once, for example after quiet hours. */
    Text summary(ProductLocale locale, int count);

    Text test(ProductLocale locale);
}
