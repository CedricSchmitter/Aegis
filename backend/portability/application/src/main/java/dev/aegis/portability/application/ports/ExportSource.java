package dev.aegis.portability.application.ports;

import java.io.IOException;

/** Read access to a generation being restored, addressed by relative paths from its manifest. */
public interface ExportSource {
    boolean exists(String relativePath);

    byte[] read(String relativePath) throws IOException;

    String location();
}
