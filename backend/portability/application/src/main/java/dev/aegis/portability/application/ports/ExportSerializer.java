package dev.aegis.portability.application.ports;

/** JSON encoding for export files; implemented by an adapter so the application stays framework-free. */
public interface ExportSerializer {
    byte[] toJson(Object value);

    /**
     * The same value as compact JSON with map entries ordered by key, so equal data always gives equal bytes. Used
     * for the data fingerprint, never written as an export file.
     */
    default byte[] toCanonicalJson(Object value) {
        return toJson(value);
    }

    <T> T fromJson(byte[] json, Class<T> type);
}
