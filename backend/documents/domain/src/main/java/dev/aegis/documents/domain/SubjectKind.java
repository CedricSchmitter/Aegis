package dev.aegis.documents.domain;

/** What a record is about. Searchable metadata only; never an access restriction. */
public enum SubjectKind {
    /** A specific household member. */
    MEMBER,
    /** The house or apartment itself. */
    HOUSE,
    /** The household as a whole (both members). */
    HOUSEHOLD
}
