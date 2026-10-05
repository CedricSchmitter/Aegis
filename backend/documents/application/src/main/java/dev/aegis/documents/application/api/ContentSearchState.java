package dev.aegis.documents.application.api;

/** Whether full-text results from the archive were included in a search response. */
public enum ContentSearchState {
    /** No query text: the list is the full collection. */
    NOT_APPLIED,
    /** All archive pages were traversed within the bounds; this is not a guarantee of semantic recall. */
    READY,
    /** Only some archive matches were collected; accumulated IDs and title matches are retained. */
    LIMITED,
    /** Archive could not be reached: only title matches are listed and the result may be incomplete. */
    UNAVAILABLE
}
