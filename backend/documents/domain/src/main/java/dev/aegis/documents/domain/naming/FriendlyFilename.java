package dev.aegis.documents.domain.naming;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, human-readable download and export names:
 * {@code <document-date>_<correspondent>_<document-type>_<short-topic>_<stable-id>.<extension>}, for example
 * {@code 2026-09-17_Telekom_Rechnung_Internet_a1b2c3d4.pdf}. Unknown components are omitted, never guessed. The
 * stable id (the first eight hex digits of the document id) keeps identical-looking documents apart; the extension
 * comes from the verified media type, never from a model or member.
 *
 * <p>Components are NFKC-normalized and lose control, format and bidi characters, path and reserved characters, and
 * tokens with five or more digits (account numbers, IBANs, customer ids). The descriptive part is bounded in UTF-8
 * bytes so the name fits Windows, Linux and browser limits. Because every name ends with {@code _<stable-id>} and the
 * extension, no name can equal a reserved Windows device name such as {@code CON} or {@code NUL}, and trailing dots
 * or spaces cannot occur.
 */
public final class FriendlyFilename {
    public static final int STABLE_ID_LENGTH = 8;
    /** Upper bound of the descriptive part in UTF-8 bytes; the id and extension are added on top. */
    public static final int MAX_DESCRIPTIVE_BYTES = 150;
    public static final int MAX_CORRESPONDENT = 40;
    public static final int MAX_TYPE = 40;
    public static final int MAX_TOPIC = 40;
    public static final int MAX_OVERRIDE = 120;
    static final String FALLBACK = "document";

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}\\p{Zl}\\p{Zp}]");
    private static final Pattern FORBIDDEN = Pattern.compile("[<>:\"/\\\\|?*]");
    private static final Pattern IBAN =
            Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,8}(?:\\s?[A-Z0-9]{1,4})?\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\S*\\d(?:[\\s./-]?\\d){4,}\\S*");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_]+");
    private static final Pattern DASHES = Pattern.compile("-{2,}");
    private static final Pattern EDGE = Pattern.compile("^[-.\\s_]+|[-.\\s_]+$");

    /** Evidenced parts of a name; null means unknown and the part is left out. */
    public record Components(LocalDate documentDate, String correspondent, String documentType, String topic) {
    }

    private FriendlyFilename() {
    }

    /**
     * Renders the canonical name. When no component is known, {@code fallbackTitle} (the display title) describes the
     * file instead; without a usable title the neutral word {@code document} is used.
     */
    public static String render(DocumentId id, Components components, String fallbackTitle, String contentType) {
        List<String> parts = new ArrayList<>();
        if (components.documentDate() != null) {
            parts.add(components.documentDate().toString());
        }
        add(parts, component(components.correspondent(), MAX_CORRESPONDENT));
        add(parts, component(components.documentType(), MAX_TYPE));
        add(parts, component(components.topic(), MAX_TOPIC));
        if (parts.isEmpty()) {
            String title = component(fallbackTitle, MAX_TOPIC + MAX_TYPE);
            parts.add(title == null ? FALLBACK : title);
        }
        return assemble(String.join("_", parts), id, contentType);
    }

    /**
     * Renders a member-chosen name. A trailing extension and a trailing stable id are removed first and added back,
     * so the id suffix is always present exactly once.
     *
     * @throws InvalidInputException when nothing usable remains
     */
    public static String renderOverride(DocumentId id, String stem, String contentType) {
        return assemble(overrideStem(id, stem), id, contentType);
    }

    /** Normalized form of a member-chosen stem as it is stored. Digits are kept: the member chose them. */
    public static String overrideStem(DocumentId id, String stem) {
        if (stem == null) {
            throw new InvalidInputException("document.filename.missing", "Filename is required");
        }
        String value = Normalizer.normalize(stem, Normalizer.Form.NFKC).strip();
        for (String extension : List.of(".pdf", ".bin")) {
            if (value.toLowerCase(Locale.ROOT).endsWith(extension)) {
                value = value.substring(0, value.length() - extension.length());
            }
        }
        String suffix = "_" + stableId(id);
        if (value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            value = value.substring(0, value.length() - suffix.length());
        }
        value = INVISIBLE.matcher(value).replaceAll(" ");
        value = FORBIDDEN.matcher(value).replaceAll(" ");
        value = value.replaceAll("\\s+", " ").strip().replace(' ', '-');
        value = DASHES.matcher(value).replaceAll("-");
        value = EDGE.matcher(value).replaceAll("");
        value = truncateChars(value, MAX_OVERRIDE);
        value = EDGE.matcher(value).replaceAll("");
        if (value.isEmpty()) {
            throw new InvalidInputException("document.filename.invalid", "Filename has no usable characters");
        }
        return value;
    }

    /** Same name with the full document id instead of the short one; used when two names in one export collide. */
    public static String withFullId(String filename, DocumentId id) {
        String shortSuffix = "_" + stableId(id);
        int at = filename.lastIndexOf(shortSuffix);
        if (at < 0) {
            return filename;
        }
        String full = "_" + id.value().toString().replace("-", "");
        return filename.substring(0, at) + full + filename.substring(at + shortSuffix.length());
    }

    public static String stableId(DocumentId id) {
        return id.value().toString().replace("-", "").substring(0, STABLE_ID_LENGTH);
    }

    /** File extension of a verified media type. Aegis stores PDFs only; anything else is served as binary. */
    public static String extensionFor(String contentType) {
        return "application/pdf".equalsIgnoreCase(contentType) ? ".pdf" : ".bin";
    }

    /**
     * ASCII-only variant for the legacy {@code filename} parameter of Content-Disposition; the full name travels in
     * the RFC 5987 {@code filename*} parameter.
     */
    public static String asciiFallback(String filename) {
        String replaced = filename.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        String stripped = Normalizer.normalize(replaced, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder ascii = new StringBuilder();
        stripped.codePoints().forEach(cp -> ascii.append(cp >= 0x20 && cp < 0x7f && cp != '"' && cp != '\\'
                && cp != ';' && cp != '%' ? (char) cp : '_'));
        return ascii.toString();
    }

    /** One sanitized name component, or null when nothing usable remains. */
    public static String component(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        value = INVISIBLE.matcher(value).replaceAll(" ");
        value = IBAN.matcher(value).replaceAll(" ");
        value = LONG_NUMBER.matcher(value).replaceAll(" ");
        value = FORBIDDEN.matcher(value).replaceAll(" ");
        value = SEPARATORS.matcher(value.strip()).replaceAll("-");
        value = DASHES.matcher(value).replaceAll("-");
        value = EDGE.matcher(value).replaceAll("");
        value = truncateChars(value, maxChars);
        value = EDGE.matcher(value).replaceAll("");
        return value.isEmpty() ? null : value;
    }

    private static String assemble(String descriptive, DocumentId id, String contentType) {
        String bounded = EDGE.matcher(truncateBytes(descriptive, MAX_DESCRIPTIVE_BYTES)).replaceAll("");
        if (bounded.isEmpty()) {
            bounded = FALLBACK;
        }
        return bounded + "_" + stableId(id) + extensionFor(contentType);
    }

    private static void add(List<String> parts, String value) {
        if (value != null) {
            parts.add(value);
        }
    }

    private static String truncateChars(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String truncateBytes(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            result.appendCodePoint(cp);
            bytes += size;
            i += Character.charCount(cp);
        }
        return result.toString();
    }
}
