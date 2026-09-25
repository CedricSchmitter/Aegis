package dev.aegis.documents.domain;

/**
 * What a record is about. Searchable metadata only; never an access restriction. A record about several persons
 * carries one {@link #MEMBER} subject per person; there is no separate value for "both" (ADR-0017).
 */
public enum SubjectKind {
    /** A specific person of the household. */
    MEMBER,
    /** The house or apartment itself. */
    HOUSE
}
