package dev.aegis.documents.domain.catalog;

/** Lifecycle of a vocabulary entry. */
public enum LabelStatus {
    /** Offered for new assignments. */
    ACTIVE,
    /** Kept on existing documents and searchable, but not offered for new assignments. */
    RETIRED,
    /** Folded into another label; kept only as a redirect for stable references. */
    MERGED
}
