package dev.aegis.documents.application.api;

/** Whether full-text results from the archive were included in a search response. */
public enum ContentSearchState {
    /** No query text: the list is the full collection. */
    NOT_APPLIED,
    /** Archive full-text search answered; results include content matches of archived documents. */
    READY,
    /** Archive could not be reached: only title matches are listed and the result may be incomplete. */
    UNAVAILABLE
}
