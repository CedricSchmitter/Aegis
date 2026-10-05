package dev.aegis.documents.domain.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Immutable derived text: offsets refer to the unchanged original, joined with one newline between pages. */
public record PassageRevision(String revision, String sourceFingerprint, String sourceKind, String text,
        boolean paged, List<Passage> passages) {
    public static final String EXTRACTION_VERSION = "pdfbox-ocr-2";
    public static final String CHUNK_VERSION = "paragraph-1";
    public static final int MAX_PASSAGE = 2_000;
    public static final int OVERLAP = 200;

    public PassageRevision {
        passages = List.copyOf(passages);
        if (text == null || text.length() > 2_000_000 || passages.size() > 4_000) {
            throw new IllegalArgumentException("derived text exceeds its bounds");
        }
        for (int ordinal = 0; ordinal < passages.size(); ordinal++) {
            Passage p = passages.get(ordinal);
            if (p.ordinal() != ordinal || p.start() < 0 || p.end() > text.length() || p.end() <= p.start()
                    || p.original().length() > MAX_PASSAGE || !p.original().equals(text.substring(p.start(), p.end()))
                    || (paged && (p.startPage() == null || p.endPage() == null || p.startPage() < 1 || p.endPage() < p.startPage()))
                    || (!paged && (p.startPage() != null || p.endPage() != null))) {
                throw new IllegalArgumentException("passage does not match its original text provenance");
            }
        }
    }

    public record Passage(int ordinal, int start, int end, Integer startPage, Integer endPage, String original,
            String normalized, List<String> identifiers) {
        public Passage { identifiers = List.copyOf(identifiers); }
    }

    public static PassageRevision of(String fingerprint, String kind, List<String> pages, boolean paged) {
        if (pages.isEmpty() || pages.size() > 1_000 || (!paged && pages.size() != 1)) {
            throw new IllegalArgumentException("derived text has invalid page bounds");
        }
        String text = String.join("\n", pages);
        if (text.length() > 2_000_000) { throw new IllegalArgumentException("derived text exceeds its character bound"); }
        String revision = hash(fingerprint + "\n" + EXTRACTION_VERSION + "\n" + CHUNK_VERSION + "\n" + kind
                + "\n" + paged + "\n" + pages.stream().map(String::length).toList() + "\n" + text);
        List<Passage> chunks = new ArrayList<>();
        int pageOffset = 0;
        for (int page = 0; page < pages.size(); page++) {
            String value = pages.get(page);
            int start = 0;
            while (start < value.length()) {
                // Prefer the last paragraph boundary in a window, while combining very short paragraphs.
                int end = Math.min(value.length(), start + MAX_PASSAGE);
                if (end < value.length()) {
                    int paragraph = value.lastIndexOf("\n\n", end - 2);
                    if (paragraph >= start + MAX_PASSAGE / 2) { end = paragraph + 2; }
                    if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) { end--; }
                }
                String original = value.substring(start, end);
                if (!original.isBlank()) {
                    chunks.add(new Passage(chunks.size(), pageOffset + start, pageOffset + end,
                            paged ? page + 1 : null, paged ? page + 1 : null, original,
                            normalize(original), identifiers(original)));
                }
                start = end < value.length() ? end - OVERLAP : end;
                if (start > 0 && start < value.length() && Character.isLowSurrogate(value.charAt(start)) && Character.isHighSurrogate(value.charAt(start - 1))) { start--; }
            }
            pageOffset += value.length() + 1;
        }
        return new PassageRevision(revision, fingerprint, kind, text, paged, chunks);
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("ß", "ss").replaceAll("[^a-z0-9]+", " ").strip();
    }

    public static String identifier(String value) { return normalize(value).replace(" ", ""); }

    public static List<String> identifiers(String original) {
        var result = new java.util.LinkedHashSet<String>();
        // Keep names literal and punctuation-separated reference numbers compact, without stemming.
        var matcher = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+(?:[-/._][\\p{L}\\p{N}]+)*")
                .matcher(original);
        while (matcher.find()) {
            String token = identifier(matcher.group());
            if (!token.isEmpty()) { result.add(token); }
        }
        return List.copyOf(result);
    }

    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
