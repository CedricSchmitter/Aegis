package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.application.push.PushTexts;
import dev.aegis.notifications.domain.NoticeKind;
import dev.aegis.shared.ProductLocale;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Push texts from the {@code push/messages} catalogs in German and English. Parameters are filled in by name; a date
 * parameter such as a cancellation deadline is formatted for the locale. A missing parameter leaves an empty value
 * rather than a placeholder.
 */
@Component
public class BundlePushTexts implements PushTexts {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    @Override
    public Text notice(ProductLocale locale, NoticeKind kind, Map<String, String> params, boolean details) {
        ResourceBundle bundle = bundle(locale);
        String body = bundle.getString(kind.name() + (details ? ".details" : ".generic"));
        return new Text(bundle.getString("title"), fill(body, params, javaLocale(locale)));
    }

    @Override
    public Text summary(ProductLocale locale, int count) {
        ResourceBundle bundle = bundle(locale);
        return new Text(bundle.getString("title"),
                fill(bundle.getString("summary"), Map.of("count", Integer.toString(count)), javaLocale(locale)));
    }

    @Override
    public Text test(ProductLocale locale) {
        ResourceBundle bundle = bundle(locale);
        return new Text(bundle.getString("title"), bundle.getString("test"));
    }

    static ResourceBundle bundle(ProductLocale locale) {
        return ResourceBundle.getBundle("push.messages", javaLocale(locale),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    static Locale javaLocale(ProductLocale locale) {
        return locale == ProductLocale.EN ? Locale.ENGLISH : Locale.GERMAN;
    }

    private static String fill(String template, Map<String, String> params, Locale locale) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = params.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(formatted(value, locale)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String formatted(String value, Locale locale) {
        if (value.length() == 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            try {
                return LocalDate.parse(value).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                        .withLocale(locale));
            } catch (DateTimeParseException notADate) {
                return value;
            }
        }
        return value;
    }
}
