package dev.aegis.documents.domain.classification;

/** Who proposed a label assignment. */
public enum AssignmentOrigin {
    /** A household member chose the label. */
    MANUAL,
    /** An automatic classifier proposed or applied the label. */
    AUTOMATIC
}
