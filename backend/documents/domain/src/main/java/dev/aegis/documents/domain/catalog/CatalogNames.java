package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and matching of user-authored folder and label names. Names keep their original language and
 * spelling; the match key only folds width, case and whitespace so that equal names are detected, while similar
 * names stay distinct.
 */
public final class CatalogNames {
    public static final int MAX_LENGTH = 80;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private CatalogNames() {
    }

    /** Trimmed name with collapsed whitespace; rejects blank, control characters and overlong values. */
    public static String requireName(String value, String codePrefix) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(codePrefix + ".name.missing", "Name is required");
        }
        String normalized = WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC).strip())
                .replaceAll(" ");
        if (CONTROL.matcher(normalized).find()) {
            throw new InvalidInputException(codePrefix + ".name.invalid", "Name contains control characters");
        }
        if (normalized.length() > MAX_LENGTH) {
            throw new InvalidInputException(codePrefix + ".name.tooLong", "Name exceeds " + MAX_LENGTH);
        }
        return normalized;
    }

    /** Comparison key: NFKC, trimmed, single spaces, lower case. */
    public static String matchKey(String value) {
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        return WHITESPACE.matcher(folded).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
