package dev.aegis.documents.domain.analysis;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic checks that a classifier's claim is backed by the document text. A quote counts as evidence only if
 * it occurs in the OCR text after folding width, case, whitespace and soft hyphens; a date counts only if its quote
 * contains that date in a common German or English notation. Model statements alone never establish a fact.
 */
public final class Evidence {
    public static final int MIN_QUOTE = 3;
    public static final int MAX_QUOTE = 200;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String[] MONTHS_DE = {"januar", "februar", "märz", "april", "mai", "juni", "juli",
        "august", "september", "oktober", "november", "dezember"};
    private static final String[] MONTHS_EN = {"january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"};

    private Evidence() {
    }

    /** Whether {@code quote} is a short verbatim excerpt of {@code text}. */
    public static boolean quoted(String quote, String text) {
        if (quote == null || text == null) {
            return false;
        }
        String needle = fold(quote);
        if (needle.length() < MIN_QUOTE || needle.length() > MAX_QUOTE) {
            return false;
        }
        return fold(text).contains(needle);
    }

    /** Whether the quote is verbatim document text and spells out {@code date}. */
    public static boolean datesTo(LocalDate date, String quote, String text) {
        if (date == null || !quoted(quote, text)) {
            return false;
        }
        String folded = fold(quote).replace("maerz", "märz");
        return notations(date).stream().anyMatch(folded::contains);
    }

    static List<String> notations(LocalDate date) {
        int d = date.getDayOfMonth();
        int m = date.getMonthValue();
        int y = date.getYear();
        String dd = two(d);
        String mm = two(m);
        String yy = two(y % 100);
        String de = MONTHS_DE[m - 1];
        String en = MONTHS_EN[m - 1];
        List<String> forms = new ArrayList<>(List.of(date.toString(),
                dd + "." + mm + "." + y, d + "." + m + "." + y, dd + "." + mm + "." + yy,
                dd + "/" + mm + "/" + y, d + ". " + de + " " + y, d + " " + de + " " + y, dd + ". " + de + " " + y,
                en + " " + d + ", " + y, d + " " + en + " " + y, en + " " + dd + ", " + y));
        return forms;
    }

    static String fold(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).replace("­", "");
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
