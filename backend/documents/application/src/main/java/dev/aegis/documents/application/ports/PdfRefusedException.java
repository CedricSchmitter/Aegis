package dev.aegis.documents.application.ports;

/** A PDF the page editor cannot use: it needs a password or cannot be parsed. The code is shown localized. */
public class PdfRefusedException extends RuntimeException {
    public static final String ENCRYPTED = "pageEdit.source.encrypted";
    public static final String UNREADABLE = "pageEdit.source.unreadable";

    private final String code;

    public PdfRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
