package dev.aegis.documents.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * File formats Aegis accepts as a document original (ADR-0011). The format is recognized from the first bytes of
 * the content only; the uploaded name and the reported media type are never trusted. Each format is one the archive
 * (Paperless-ngx) consumes: it keeps the file as the original and generates a searchable archive PDF, so a photo of
 * a letter stays the original while the archive supplies its text.
 *
 * <p>The signatures follow what the archive's content detection (libmagic) accepts, so Aegis does not acknowledge a
 * file that the archive would refuse. HEIC is recognized by the major brand {@code heic} or {@code heix}, as phone
 * cameras write it; a generic HEIF container ({@code mif1}) or AVIF is detected as a different type by the archive
 * and is therefore not accepted. Beyond the header, images are not decoded here: damage further inside a file
 * surfaces as a failed archival.
 */
public enum OriginalFormat {
    PDF("application/pdf", ".pdf", List.of(".pdf")),
    JPEG("image/jpeg", ".jpg", List.of(".jpg", ".jpeg", ".jpe", ".jfif")),
    PNG("image/png", ".png", List.of(".png")),
    WEBP("image/webp", ".webp", List.of(".webp")),
    HEIC("image/heic", ".heic", List.of(".heic", ".heif"));

    /** Number of leading bytes {@link #detect(byte[])} needs to recognize every format. */
    public static final int SIGNATURE_LENGTH = 16;

    private static final byte[] PDF_SIGNATURE = ascii("%PDF-");
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** The first PNG chunk is always IHDR with a 13-byte body; a mismatch means a damaged header. */
    private static final byte[] PNG_HEADER_CHUNK = {0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final List<String> WEBP_CHUNKS = List.of("VP8 ", "VP8L", "VP8X");
    private static final List<String> HEIC_BRANDS = List.of("heic", "heix");

    private final String contentType;
    private final String extension;
    private final List<String> knownExtensions;

    OriginalFormat(String contentType, String extension, List<String> knownExtensions) {
        this.contentType = contentType;
        this.extension = extension;
        this.knownExtensions = knownExtensions;
    }

    /**
     * Recognizes the format from the leading bytes of a file ({@link #SIGNATURE_LENGTH} are enough; fewer are fine
     * for a shorter file).
     *
     * @return empty when the content matches no accepted format, including a header that is cut off or damaged
     */
    public static Optional<OriginalFormat> detect(byte[] head) {
        if (startsWith(head, 0, PDF_SIGNATURE)) {
            return Optional.of(PDF);
        }
        if (startsWith(head, 0, JPEG_SIGNATURE)) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, 0, PNG_SIGNATURE)) {
            return startsWith(head, PNG_SIGNATURE.length, PNG_HEADER_CHUNK) ? Optional.of(PNG) : Optional.empty();
        }
        if (text(head, 0, 4).equals("RIFF") && text(head, 8, 4).equals("WEBP")) {
            return WEBP_CHUNKS.contains(text(head, 12, 4)) ? Optional.of(WEBP) : Optional.empty();
        }
        if (text(head, 4, 4).equals("ftyp") && HEIC_BRANDS.contains(text(head, 8, 4)) && boxSize(head) >= 16) {
            return Optional.of(HEIC);
        }
        return Optional.empty();
    }

    /** The format stored under this media type, for example to name a file; empty for anything else. */
    public static Optional<OriginalFormat> ofContentType(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(format -> format.contentType.equals(normalized)).findFirst();
    }

    /**
     * The name without a trailing extension of any accepted format ({@code Scan.JPG} becomes {@code Scan}); other
     * names are returned unchanged.
     */
    public static String withoutKnownExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (OriginalFormat format : values()) {
            for (String known : format.knownExtensions) {
                if (lower.endsWith(known)) {
                    return filename.substring(0, filename.length() - known.length());
                }
            }
        }
        return filename;
    }

    public String contentType() {
        return contentType;
    }

    /** Canonical file extension including the dot, for example {@code .jpg}. */
    public String extension() {
        return extension;
    }

    public boolean isImage() {
        return this != PDF;
    }

    private static boolean startsWith(byte[] head, int offset, byte[] expected) {
        if (head.length < offset + expected.length) {
            return false;
        }
        return Arrays.equals(head, offset, offset + expected.length, expected, 0, expected.length);
    }

    private static String text(byte[] head, int offset, int length) {
        if (head.length < offset + length) {
            return "";
        }
        return new String(head, offset, length, StandardCharsets.ISO_8859_1);
    }

    /** Declared size of the first ISO base media box (big endian); the ftyp box needs at least 16 bytes. */
    private static long boxSize(byte[] head) {
        return ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8) | (head[3] & 0xFFL);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
