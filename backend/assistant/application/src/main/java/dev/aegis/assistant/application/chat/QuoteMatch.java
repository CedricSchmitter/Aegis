package dev.aegis.assistant.application.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds a model's quote in a document's archived text. OCR text and quotes differ in ways that do not change what is
 * quoted: case, line breaks and runs of blanks, words hyphenated across lines, typographic quotation marks and dashes.
 * Those are ignored; anything else must match. An ellipsis in the quote skips text, and the parts must appear in
 * order. The archive separates pages with form feeds; the page of a match is counted from them.
 */
final class QuoteMatch {
    private static final Pattern ELLIPSIS = Pattern.compile("\\.\\.\\.|…");
    private static final char PAGE_BREAK = '\f';

    private QuoteMatch() {
    }

    /** Where the quote was found: its 1-based page, or empty when the text does not mark pages. */
    record Found(Optional<Integer> page) {
    }

    static Optional<Found> find(String text, String quote) {
        Normalized haystack = Normalized.of(text);
        List<String> parts = new ArrayList<>();
        for (String part : ELLIPSIS.split(quote, -1)) {
            String normalized = Normalized.of(part).value.strip();
            if (!normalized.isEmpty()) {
                parts.add(normalized);
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        int from = 0;
        int first = -1;
        for (String part : parts) {
            int at = haystack.value.indexOf(part, from);
            if (at < 0) {
                return Optional.empty();
            }
            if (first < 0) {
                first = at;
            }
            from = at + part.length();
        }
        if (text.indexOf(PAGE_BREAK) < 0) {
            return Optional.of(new Found(Optional.empty()));
        }
        int original = haystack.origin[first];
        int page = 1;
        for (int i = 0; i < original; i++) {
            if (text.charAt(i) == PAGE_BREAK) {
                page++;
            }
        }
        return Optional.of(new Found(Optional.of(page)));
    }

    /** The comparable form of a text plus, for every character of it, its index in the original. */
    private static final class Normalized {
        final String value;
        final int[] origin;

        private Normalized(String value, int[] origin) {
            this.value = value;
            this.origin = origin;
        }

        static Normalized of(String text) {
            StringBuilder out = new StringBuilder(text.length());
            int[] origin = new int[text.length() + 1];
            boolean blank = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '­') {
                    continue;
                }
                if ((c == '-' || c == '‐') && followedByLineBreak(text, i)) {
                    i = skipBlanks(text, i + 1) - 1;
                    continue;
                }
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    blank = true;
                    continue;
                }
                if (blank && !out.isEmpty()) {
                    origin[out.length()] = i;
                    out.append(' ');
                }
                blank = false;
                origin[out.length()] = i;
                out.append(fold(c));
            }
            return new Normalized(out.toString(), origin);
        }

        private static boolean followedByLineBreak(String text, int index) {
            int next = index + 1;
            while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) {
                next++;
            }
            return next < text.length() && (text.charAt(next) == '\n' || text.charAt(next) == '\r'
                    || text.charAt(next) == PAGE_BREAK);
        }

        private static int skipBlanks(String text, int index) {
            int next = index;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            return next;
        }

        /** Exactly one character, so the origin index stays aligned with the original text. */
        private static char fold(char c) {
            char folded = switch (c) {
                case '“', '”', '„', '«', '»' -> '"';
                case '‘', '’', '‚', '‹', '›' -> '\'';
                case '–', '—', '−' -> '-';
                default -> c;
            };
            String normalized = Normalizer.normalize(String.valueOf(folded), Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
            return normalized.length() == 1 ? normalized.charAt(0) : Character.toLowerCase(folded);
        }
    }
}
