package dev.aegis.assistant.adapters.claudecode;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Claude Code (subscription) provider. {@code command} is the unmodified official CLI binary;
 * {@code tokenFile} holds the owner's {@code claude setup-token} OAuth token.
 */
@ConfigurationProperties(prefix = "aegis.ai.providers.claude-code")
public record ClaudeCodeProperties(List<String> command, String tokenFile, Integer maxTurns) {
    public ClaudeCodeProperties {
        command = command == null || command.isEmpty() ? List.of("/opt/claude/claude") : List.copyOf(command);
        tokenFile = tokenFile == null || tokenFile.isBlank() ? "/run/secrets/ai/claude-code-oauth-token" : tokenFile;
        maxTurns = maxTurns == null ? 3 : maxTurns;
    }
}
