package dev.aegis.assistant.adapters.claudecode;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Claude Code (subscription) provider. {@code runnerSocket} is the Unix socket of the host runner
 * ({@code ops/claude-runner}) that executes {@code claude -p} with the owner's own CLI login; {@code members} are the
 * usernames whose runs may use the owner's personal subscription.
 */
@ConfigurationProperties(prefix = "aegis.ai.providers.claude-code")
public record ClaudeCodeProperties(String runnerSocket, List<String> members, Integer maxTurns) {
    public ClaudeCodeProperties {
        runnerSocket = runnerSocket == null || runnerSocket.isBlank() ? "/run/aegis-claude/runner.sock"
                : runnerSocket;
        members = members == null ? List.of() : members.stream().map(String::strip).filter(name -> !name.isEmpty())
                .map(name -> name.toLowerCase(Locale.ROOT)).distinct().toList();
        maxTurns = maxTurns == null ? 3 : maxTurns;
    }
}
