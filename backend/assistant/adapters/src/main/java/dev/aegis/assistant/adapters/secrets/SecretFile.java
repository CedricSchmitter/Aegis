package dev.aegis.assistant.adapters.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * Reads a single-line secret (API key or token) from its file and re-reads it when the file changes, so the owner
 * can add or rotate it without a restart. The value is never logged or returned in an error.
 */
public final class SecretFile {
    private final Path path;
    private volatile Cached cached;

    private record Cached(FileTime modified, long size, Optional<String> key) {
    }

    public SecretFile(Path path) {
        this.path = path;
    }

    public Optional<String> read() {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                return Optional.empty();
            }
            FileTime modified = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            Cached current = cached;
            if (current != null && current.modified().equals(modified) && current.size() == size) {
                return current.key();
            }
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            Optional<String> key = content.isEmpty() || content.chars().anyMatch(Character::isWhitespace)
                    ? Optional.empty() : Optional.of(content);
            cached = new Cached(modified, size, key);
            return key;
        } catch (IOException | SecurityException unreadable) {
            return Optional.empty();
        }
    }

    public Path path() {
        return path;
    }
}
