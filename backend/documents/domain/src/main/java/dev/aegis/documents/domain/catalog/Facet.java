package dev.aegis.documents.domain.catalog;

import dev.aegis.shared.InvalidInputException;

/**
 * Label facet. Canonical facets describe what a document is and who or what it concerns; tags are optional
 * free-form additions that must not duplicate canonical values. Subjects (house, household, members) and the
 * folder are separate facets outside the label vocabulary.
 */
public enum Facet {
    /** What kind of document it is (invoice, contract, …); at most one own value per document. */
    DOCUMENT_TYPE(true),
    /** Correspondent or organization involved (Telekom, …). */
    ORGANIZATION(false),
    /** What the document is about (internet, water, …). */
    TOPIC(false),
    /** Free-form household tag. */
    TAG(false);

    private final boolean singleValued;

    Facet(boolean singleValued) {
        this.singleValued = singleValued;
    }

    public boolean singleValued() {
        return singleValued;
    }

    public boolean canonical() {
        return this != TAG;
    }

    public static Facet parse(String value) {
        try {
            return Facet.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidInputException("label.facet.invalid", "Unknown facet " + value);
        }
    }
}
