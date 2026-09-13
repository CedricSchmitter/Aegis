package dev.aegis.portability.application.ports;

/** JSON encoding for export files; implemented by an adapter so the application stays framework-free. */
public interface ExportSerializer {
    byte[] toJson(Object value);

    <T> T fromJson(byte[] json, Class<T> type);
}
