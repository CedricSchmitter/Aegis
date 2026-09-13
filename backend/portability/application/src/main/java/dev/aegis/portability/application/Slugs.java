package dev.aegis.portability.application;

import java.text.Normalizer;
import java.util.Locale;

/** Filesystem-safe, readable names for exported originals. */
final class Slugs {
    private Slugs() {
    }

    static String slug(String text, int maxLength) {
        String replaced = text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae")
                .replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        String ascii = Normalizer.normalize(replaced, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "document";
        }
        return slug.length() > maxLength ? slug.substring(0, maxLength).replaceAll("-$", "") : slug;
    }
}
