package dev.aegis.assistant.application.chat;

import dev.aegis.documents.application.api.DocumentTextQuery.DocumentText;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Finds the passages of one document's archived text that contain the most of a few search words, so the assistant
 * can go straight to a clause near the end of a long document. Matching is deterministic and ignores case, accents
 * and umlaut dots, {@code ß} versus {@code ss}, and words hyphenated across lines. A word of letters matches inside
 * longer words, as German compounds need ("frist" in "Kündigungsfrist"); a word shorter than
 * {@value #WHOLE_WORD_BELOW} letters matches whole words only. A word with digits or inner separators is an
 * identifier and matches regardless of separators, so {@code K-2026/004} finds {@code K2026004} and {@code K 2026 004}.
 *
 * <p>A passage holds the matches within {@value #SPAN} characters plus about {@value #CONTEXT} characters of context
 * on either side. Passages rank by the number of distinct words they contain, then by their number of matches, then
 * by position; passages never share text.
 */
public final class PassageSearch {
    public static final int MAX_TERMS = 8;
    static final int MIN_TERM = 2;
    static final int WHOLE_WORD_BELOW = 4;
    static final int SPAN = 500;
    public static final int CONTEXT = 250;
    /** How far an excerpt may move to start or end at a blank instead of inside a word. */
    static final int BOUNDARY_WINDOW = 40;

    private PassageSearch() {
    }

    /** A search word: as written, its comparable form, and whether it is matched without regard to separators. */
    public record Term(String word, String key, boolean identifier) {
    }

    /**
     * Characters {@code [start, end)} of {@link DocumentText#text()}; {@code page} is the page of its first match when
     * the pages are known. {@code terms} are the words it contains, in query order.
     */
    public record Passage(int start, int end, OptionalInt page, List<String> terms, int matches) {
    }

    /** The best passages first; {@code omitted} counts further passages with matches that were not returned. */
    public record Result(List<Term> terms, List<String> missingTerms, List<Passage> passages, int omitted) {
    }

    /** The distinct search words of a query; words without at least {@value #MIN_TERM} letters or digits are dropped. */
    public static List<Term> terms(String query) {
        Map<String, Term> terms = new LinkedHashMap<>();
        for (String word : query.strip().split("\\s+")) {
            Folded folded = Folded.of(word);
            String key = folded.compact;
            if (key.length() < MIN_TERM) {
                continue;
            }
            boolean identifier = key.chars().anyMatch(Character::isDigit) || folded.spaced.contains(" ");
            terms.putIfAbsent((identifier ? "#" : "") + key, new Term(word, key, identifier));
        }
        return List.copyOf(terms.values());
    }

    public static Result find(DocumentText document, List<Term> terms, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (terms.isEmpty() || terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException("between 1 and " + MAX_TERMS + " search words are required");
        }
        String content = document.text();
        Folded text = Folded.of(content);
        List<Match> matches = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int t = 0; t < terms.size(); t++) {
            int before = matches.size();
            collect(text, terms.get(t), t, matches);
            if (matches.size() == before) {
                missing.add(terms.get(t).word());
            }
        }
        matches.sort(Comparator.comparingInt(Match::start).thenComparingInt(Match::term));
        List<Window> windows = windows(matches, terms.size());
        windows.sort(Comparator.comparingInt(Window::distinct).reversed()
                .thenComparing(Comparator.comparingInt(Window::count).reversed())
                .thenComparingInt(Window::start));
        TreeMap<Integer, Integer> taken = new TreeMap<>();
        List<Passage> passages = new ArrayList<>();
        int omitted = 0;
        for (Window window : windows) {
            int start = excerptStart(content, window.start());
            int end = excerptEnd(content, window.end());
            if (overlaps(taken, start, end)) {
                continue;
            }
            taken.put(start, end);
            if (passages.size() >= limit) {
                omitted++;
                continue;
            }
            List<String> found = new ArrayList<>();
            for (int t = 0; t < terms.size(); t++) {
                if (window.terms()[t]) {
                    found.add(terms.get(t).word());
                }
            }
            passages.add(new Passage(start, end, document.pageAt(window.start()), List.copyOf(found),
                    window.count()));
        }
        return new Result(terms, List.copyOf(missing), List.copyOf(passages), omitted);
    }

    private record Match(int start, int end, int term) {
    }

    /**
     * Matches from {@code start} to {@code end} in the original text; {@code terms} marks which search words occur
     * among them.
     */
    private record Window(int start, int end, int distinct, int count, boolean[] terms) {
    }

    private static void collect(Folded text, Term term, int index, List<Match> matches) {
        String haystack = term.identifier() ? text.compact : text.spaced;
        int[] origin = term.identifier() ? text.compactOrigin : text.spacedOrigin;
        String key = term.key();
        boolean whole = !term.identifier() && key.length() < WHOLE_WORD_BELOW;
        for (int at = haystack.indexOf(key); at >= 0; at = haystack.indexOf(key, at + 1)) {
            if (whole && !(boundary(haystack, at - 1) && boundary(haystack, at + key.length()))) {
                continue;
            }
            matches.add(new Match(origin[at], origin[at + key.length() - 1] + 1, index));
            at += key.length() - 1;
        }
    }

    private static boolean boundary(String haystack, int index) {
        return index < 0 || index >= haystack.length() || haystack.charAt(index) == ' ';
    }

    /** For every match, the window of the matches that start at most {@link #SPAN} characters after it. */
    private static List<Window> windows(List<Match> matches, int termCount) {
        List<Window> windows = new ArrayList<>(matches.size());
        int[] counts = new int[termCount];
        int distinct = 0;
        int to = 0;
        for (int from = 0; from < matches.size(); from++) {
            while (to < matches.size() && matches.get(to).start() - matches.get(from).start() <= SPAN) {
                if (counts[matches.get(to).term()]++ == 0) {
                    distinct++;
                }
                to++;
            }
            int end = 0;
            boolean[] present = new boolean[termCount];
            for (int t = 0; t < termCount; t++) {
                present[t] = counts[t] > 0;
            }
            for (int i = from; i < to; i++) {
                end = Math.max(end, matches.get(i).end());
            }
            windows.add(new Window(matches.get(from).start(), end, distinct, to - from, present));
            if (--counts[matches.get(from).term()] == 0) {
                distinct--;
            }
        }
        return windows;
    }

    private static boolean overlaps(TreeMap<Integer, Integer> taken, int start, int end) {
        Map.Entry<Integer, Integer> before = taken.floorEntry(start);
        if (before != null && before.getValue() > start) {
            return true;
        }
        Map.Entry<Integer, Integer> after = taken.ceilingEntry(start);
        return after != null && after.getKey() < end;
    }

    static int excerptStart(String content, int firstMatch) {
        int start = Math.max(0, firstMatch - CONTEXT);
        if (start == 0 || Character.isWhitespace(content.charAt(start - 1))) {
            return start;
        }
        for (int i = start; i < Math.min(firstMatch, start + BOUNDARY_WINDOW); i++) {
            if (Character.isWhitespace(content.charAt(i))) {
                return i + 1;
            }
        }
        return start;
    }

    static int excerptEnd(String content, int lastMatchEnd) {
        int end = Math.min(content.length(), lastMatchEnd + CONTEXT);
        if (end == content.length() || Character.isWhitespace(content.charAt(end))) {
            return end;
        }
        for (int i = end; i > Math.max(lastMatchEnd, end - BOUNDARY_WINDOW); i--) {
            if (Character.isWhitespace(content.charAt(i - 1))) {
                return i - 1;
            }
        }
        return end;
    }

    /**
     * The comparable forms of a text with, for every character, its index in the original: {@code spaced} keeps one
     * blank between words, {@code compact} drops every separator.
     */
    private static final class Folded {
        final String spaced;
        final int[] spacedOrigin;
        final String compact;
        final int[] compactOrigin;

        private Folded(String spaced, int[] spacedOrigin, String compact, int[] compactOrigin) {
            this.spaced = spaced;
            this.spacedOrigin = spacedOrigin;
            this.compact = compact;
            this.compactOrigin = compactOrigin;
        }

        static Folded of(String text) {
            StringBuilder spaced = new StringBuilder(text.length());
            StringBuilder compact = new StringBuilder(text.length());
            int[] spacedOrigin = new int[text.length() + 1];
            int[] compactOrigin = new int[text.length() + 1];
            boolean blank = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '­') {
                    continue;
                }
                if ((c == '-' || c == '‐') && hyphenAtLineEnd(text, i)) {
                    while (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                        i++;
                    }
                    continue;
                }
                String folded = fold(c);
                if (folded.isEmpty()) {
                    blank = true;
                    continue;
                }
                if (blank && !spaced.isEmpty()) {
                    spacedOrigin = room(spacedOrigin, spaced.length());
                    spacedOrigin[spaced.length()] = i;
                    spaced.append(' ');
                }
                blank = false;
                for (int k = 0; k < folded.length(); k++) {
                    spacedOrigin = room(spacedOrigin, spaced.length());
                    compactOrigin = room(compactOrigin, compact.length());
                    spacedOrigin[spaced.length()] = i;
                    compactOrigin[compact.length()] = i;
                    spaced.append(folded.charAt(k));
                    compact.append(folded.charAt(k));
                }
            }
            return new Folded(spaced.toString(), spacedOrigin, compact.toString(), compactOrigin);
        }

        private static int[] room(int[] origin, int index) {
            return index < origin.length ? origin : Arrays.copyOf(origin, origin.length * 2 + 2);
        }

        private static boolean hyphenAtLineEnd(String text, int index) {
            int next = index + 1;
            while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) {
                next++;
            }
            return next < text.length() && (text.charAt(next) == '\n' || text.charAt(next) == '\r'
                    || text.charAt(next) == '\f');
        }

        /** Lowercase letters and digits without accents; {@code ß} becomes "ss"; anything else separates words. */
        private static String fold(char c) {
            if (c < 128) {
                return Character.isLetterOrDigit(c) ? String.valueOf(Character.toLowerCase(c)) : "";
            }
            if (c == 'ß' || c == 'ẞ') {
                return "ss";
            }
            if (!Character.isLetterOrDigit(c)) {
                return "";
            }
            String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKD);
            StringBuilder out = new StringBuilder(decomposed.length());
            for (int i = 0; i < decomposed.length(); i++) {
                char part = decomposed.charAt(i);
                if (Character.isLetterOrDigit(part)) {
                    out.append(part);
                }
            }
            return out.toString().toLowerCase(Locale.ROOT);
        }
    }
}
