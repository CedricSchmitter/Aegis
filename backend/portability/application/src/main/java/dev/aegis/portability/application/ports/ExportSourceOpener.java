package dev.aegis.portability.application.ports;

import java.io.IOException;

/** Opens an export generation for restore from a location string (for example a directory path). */
public interface ExportSourceOpener {
    ExportSource open(String location) throws IOException;
}
