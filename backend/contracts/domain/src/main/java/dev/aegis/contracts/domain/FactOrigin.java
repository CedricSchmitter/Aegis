package dev.aegis.contracts.domain;

/** Who stated a fact. The origin decides which review state an observation may start in. */
public enum FactOrigin {
    /** A household member entered or corrected the value. */
    MANUAL,
    /** An extraction run proposed the value; it always needs a member's decision. */
    AUTOMATIC
}
