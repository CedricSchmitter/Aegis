package dev.aegis.assistant.domain.chat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The validated answer to one question. {@code text} refers to citations by their number in square brackets, such as
 * {@code [2]}. {@code caveats} name missing, contradictory or outdated evidence in the words of the answer.
 */
public record ChatAnswer(String text, Evidence evidence, List<Citation> citations, List<String> caveats) {
    public static final int MAX_TEXT = 8_000;
    public static final int MAX_CITATIONS = 12;
    public static final int MAX_CAVEATS = 8;
    public static final int MAX_CAVEAT = 500;

    /** How far the household's records answer the question. */
    public enum Evidence {
        COMPLETE,
        PARTIAL,
        NOT_FOUND
    }

    /** What a cited source is. */
    public enum SourceKind {
        DOCUMENT,
        CONTRACT,
        TASK
    }

    /**
     * Whether Aegis found the citation in its source after the model answered: a document quote in the document's
     * archived text, a contract or task by its id. {@code NOT_IN_SOURCE} means the source exists but the quote is not
     * in it; {@code SOURCE_UNAVAILABLE} that the source could not be read or does not exist.
     */
    public enum Check {
        VERIFIED,
        NOT_IN_SOURCE,
        SOURCE_UNAVAILABLE
    }

    /**
     * One source of the answer. {@code title} is the source's title when the answer was checked; {@code page} is the
     * 1-based page of a document quote when the archive text marks pages, otherwise null.
     */
    public record Citation(int number, SourceKind kind, UUID sourceId, String title, String quote, Integer page,
            Check check) {
        public static final int MAX_QUOTE = 500;

        public Citation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(sourceId, "source id");
            Objects.requireNonNull(check, "check");
            if (number < 1) {
                throw new IllegalArgumentException("citation numbers start at 1");
            }
            if (quote != null && quote.length() > MAX_QUOTE) {
                throw new IllegalArgumentException("a quote has at most " + MAX_QUOTE + " characters");
            }
            if (page != null && page < 1) {
                throw new IllegalArgumentException("pages start at 1");
            }
        }
    }

    public ChatAnswer {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(evidence, "evidence");
        citations = List.copyOf(citations);
        caveats = List.copyOf(caveats);
        if (text.isBlank() || text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("an answer has 1 to " + MAX_TEXT + " characters");
        }
        if (citations.size() > MAX_CITATIONS || caveats.size() > MAX_CAVEATS) {
            throw new IllegalArgumentException("too many citations or caveats");
        }
        for (String caveat : caveats) {
            if (caveat.isBlank() || caveat.length() > MAX_CAVEAT) {
                throw new IllegalArgumentException("a caveat has 1 to " + MAX_CAVEAT + " characters");
            }
        }
    }
}
