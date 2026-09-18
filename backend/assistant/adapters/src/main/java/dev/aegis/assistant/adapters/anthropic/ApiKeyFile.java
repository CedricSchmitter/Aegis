package dev.aegis.assistant.adapters.anthropic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * Reads the API key from its file and re-reads it when the file changes, so the owner can add or rotate the key
 * without a restart. The key value is never logged or returned in an error.
 */
final class ApiKeyFile {
    private final Path path;
    private volatile Cached cached;

    private record Cached(FileTime modified, long size, Optional<String> key) {
    }

    ApiKeyFile(Path path) {
        this.path = path;
    }

    Optional<String> read() {
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

    Path path() {
        return path;
    }
}
