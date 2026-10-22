package dev.aegis.documents.domain.history;

/**
 * The document fields the history records (ADR-0030), with the stable code used in the API and the stored change.
 * {@code labels} holds the document's own effective labels as a sorted, comma-separated list of label ids.
 */
public enum DocumentField {
    TITLE("title"),
    FILENAME("filename"),
    FOLDER("folder"),
    LABELS("labels");

    private final String code;

    DocumentField(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static DocumentField ofCode(String code) {
        for (DocumentField field : values()) {
            if (field.code.equals(code)) {
                return field;
            }
        }
        throw new IllegalArgumentException("unknown document field " + code);
    }
}
