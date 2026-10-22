package dev.aegis.documents.domain.text;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A compact, irreversible summary of a document's archive text for finding near duplicates (issue #42), such as the
 * same letter scanned twice or once as a photo and once as a PDF. It keeps hashes, never the text: a MinHash
 * signature of the overlapping three-word sequences and the hashes of up to {@value #MAX_NUMBERS} distinct numbers
 * (amounts, dates, contract and invoice numbers), which are compared exactly. Words are compared without case and
 * accents and with {@code ß} as {@code ss}.
 *
 * <p>Two documents count as near duplicates when enough of their word sequences and of their numbers agree. The
 * numbers keep apart records that share a template but are distinct, such as monthly invoices of one provider: their
 * wording is nearly the same, but dates, amounts and invoice numbers differ. Texts with fewer than
 * {@value #MIN_SHINGLES} word sequences, such as a receipt with a few lines or OCR noise, are never compared.
 */
public final class TextFingerprint {
    /** Stored with every fingerprint; a change of the algorithm raises it, and older fingerprints are made again. */
    public static final int VERSION = 1;
    static final int SLOTS = 64;
    static final int SHINGLE = 3;
    public static final int MIN_SHINGLES = 20;
    /** Share of agreeing word sequences from which two texts may be the same document. */
    static final double SAME_WORDS = 0.5;
    /** Share of agreeing numbers from which two texts with similar wording may be the same document. */
    static final double SAME_NUMBERS = 0.8;
    /** A record with more distinct numbers keeps the smallest hashes, a uniform sample of them. */
    static final int MAX_NUMBERS = 256;
    private static final int HEADER = 1 + 4 + 4;

    private final int shingles;
    private final int[] words;
    private final int[] digits;

    private TextFingerprint(int shingles, int[] words, int[] digits) {
        this.shingles = shingles;
        this.words = words;
        this.digits = digits;
    }

    public static TextFingerprint of(String text) {
        List<String> tokens = tokens(text == null ? "" : text);
        Set<String> sequences = new HashSet<>();
        for (int i = 0; i + SHINGLE <= tokens.size(); i++) {
            sequences.add(String.join(" ", tokens.subList(i, i + SHINGLE)));
        }
        TreeSet<Integer> numberHashes = new TreeSet<>();
        for (String token : tokens) {
            if (token.chars().anyMatch(Character::isDigit)) {
                numberHashes.add((int) (mix(fnv1a(token)) >>> 32));
            }
        }
        int[] digits = numberHashes.stream().limit(MAX_NUMBERS).mapToInt(Integer::intValue).toArray();
        return new TextFingerprint(sequences.size(), signature(sequences), digits);
    }

    /** Whether the text is long enough to be compared at all. */
    public boolean comparable() {
        return shingles >= MIN_SHINGLES;
    }

    /** Estimated share of word sequences both texts contain, from 0 to 1. */
    public double wordSimilarity(TextFingerprint other) {
        return agreement(words, other.words);
    }

    /**
     * Share of the distinct numbers of both texts that occur in both (exact up to {@value #MAX_NUMBERS} numbers, then
     * estimated from the smallest hashes); 1 when neither contains one, 0 when only one does.
     */
    public double numberSimilarity(TextFingerprint other) {
        if (digits.length == 0 || other.digits.length == 0) {
            return digits.length == other.digits.length ? 1 : 0;
        }
        // Both complete: the exact share. Otherwise the share among the smallest hashes of both (a bottom-k sketch).
        int limit = digits.length < MAX_NUMBERS && other.digits.length < MAX_NUMBERS ? Integer.MAX_VALUE
                : MAX_NUMBERS;
        int i = 0;
        int j = 0;
        int union = 0;
        int both = 0;
        while (union < limit && (i < digits.length || j < other.digits.length)) {
            int compared = i == digits.length ? 1 : j == other.digits.length ? -1
                    : Integer.compare(digits[i], other.digits[j]);
            if (compared == 0) {
                both++;
                i++;
                j++;
            } else if (compared < 0) {
                i++;
            } else {
                j++;
            }
            union++;
        }
        return both / (double) union;
    }

    /** Whether both texts are comparable and probably hold the same document. */
    public boolean nearDuplicateOf(TextFingerprint other) {
        return comparable() && other.comparable() && wordSimilarity(other) >= SAME_WORDS
                && numberSimilarity(other) >= SAME_NUMBERS;
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER + (SLOTS + digits.length) * 4).put((byte) VERSION)
                .putInt(shingles).putInt(digits.length);
        for (int value : words) {
            buffer.putInt(value);
        }
        for (int value : digits) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    /** The fingerprint stored as {@code bytes}, or null when it was made by another version of the algorithm. */
    public static TextFingerprint decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER + SLOTS * 4 || bytes[0] != VERSION) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 1, bytes.length - 1);
        int shingles = buffer.getInt();
        int numbers = buffer.getInt();
        if (numbers < 0 || numbers > MAX_NUMBERS || bytes.length != HEADER + (SLOTS + numbers) * 4) {
            return null;
        }
        int[] words = new int[SLOTS];
        int[] digits = new int[numbers];
        for (int i = 0; i < SLOTS; i++) {
            words[i] = buffer.getInt();
        }
        for (int i = 0; i < numbers; i++) {
            digits[i] = buffer.getInt();
        }
        return new TextFingerprint(shingles, words, digits);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TextFingerprint that && shingles == that.shingles
                && Arrays.equals(words, that.words) && Arrays.equals(digits, that.digits);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(words) + Arrays.hashCode(digits);
    }

    private static double agreement(int[] a, int[] b) {
        int same = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (a[i] == b[i]) {
                same++;
            }
        }
        return same / (double) SLOTS;
    }

    /** For every slot the smallest of the values' hashes under that slot's hash function; all ones when empty. */
    private static int[] signature(Set<String> values) {
        long[] minimum = new long[SLOTS];
        Arrays.fill(minimum, Long.MAX_VALUE);
        for (String value : values) {
            long base = fnv1a(value);
            for (int slot = 0; slot < SLOTS; slot++) {
                long hash = mix(base + (slot + 1) * 0x9E3779B97F4A7C15L);
                if (Long.compareUnsigned(hash, minimum[slot]) < 0) {
                    minimum[slot] = hash;
                }
            }
        }
        int[] signature = new int[SLOTS];
        for (int slot = 0; slot < SLOTS; slot++) {
            signature[slot] = (int) (minimum[slot] >>> 32);
        }
        return signature;
    }

    private static long fnv1a(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** The SplitMix64 finalizer, so neighbouring inputs give unrelated hashes. */
    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** Runs of letters and digits, folded; tokens of one character are mostly OCR noise and are dropped. */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                token.append(fold(c));
                continue;
            }
            if (token.length() > 1) {
                tokens.add(token.toString());
            }
            token.setLength(0);
        }
        return tokens;
    }

    private static String fold(char c) {
        if (c < 128) {
            return String.valueOf(Character.toLowerCase(c));
        }
        if (c == 'ß' || c == 'ẞ') {
            return "ss";
        }
        String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            if (Character.isLetterOrDigit(decomposed.charAt(i))) {
                out.append(decomposed.charAt(i));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
