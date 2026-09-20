package dev.aegis.documents.application;

import dev.aegis.documents.domain.naming.BuiltinLabelNames;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Configuration of the automatic document analysis. {@code automatic} decides whether new uploads are analyzed once
 * archived; it is read at upload time and can be switched at runtime (operations, tests). Filenames use one
 * household naming language so that a member's UI language never renames files.
 */
public final class AnalysisSettings {
    private volatile boolean automatic;
    private final BuiltinLabelNames.Language filenameLanguage;
    private final int maxTextChars;
    private final int maxCatalogFolders;
    private final ZoneId zone;

    public AnalysisSettings(boolean automatic, BuiltinLabelNames.Language filenameLanguage, int maxTextChars,
            int maxCatalogFolders, ZoneId zone) {
        if (maxTextChars < 1_000) {
            throw new IllegalArgumentException("max text characters must be at least 1000");
        }
        if (maxCatalogFolders < 1) {
            throw new IllegalArgumentException("max catalog folders must be at least 1");
        }
        this.automatic = automatic;
        this.filenameLanguage = Objects.requireNonNull(filenameLanguage);
        this.maxTextChars = maxTextChars;
        this.maxCatalogFolders = maxCatalogFolders;
        this.zone = Objects.requireNonNull(zone);
    }

    public boolean automatic() {
        return automatic;
    }

    public void setAutomatic(boolean automatic) {
        this.automatic = automatic;
    }

    public BuiltinLabelNames.Language filenameLanguage() {
        return filenameLanguage;
    }

    /** Longest OCR text handed to the classifier; longer text is cut with a visible marker. */
    public int maxTextChars() {
        return maxTextChars;
    }

    /**
     * How many folders the classifier prompt may list. Beyond this bound the catalog is reduced (empty subtrees
     * first, then the deepest folders) and the prompt says how many folders were left out.
     */
    public int maxCatalogFolders() {
        return maxCatalogFolders;
    }

    public ZoneId zone() {
        return zone;
    }
}
