package dev.aegis.assistant.adapters.anthropic;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Anthropic API settings. The API key is read from a server-side file that the owner places on the host; it is never
 * part of the repository, the environment file, logs or the database. A missing or empty file means "not
 * configured".
 */
@ConfigurationProperties(prefix = "aegis.ai.providers.anthropic")
public record AnthropicProperties(String apiKeyFile, String baseUrl, Duration connectTimeout) {
    public AnthropicProperties {
        apiKeyFile = apiKeyFile == null || apiKeyFile.isBlank() ? "/run/secrets/ai/anthropic-api-key" : apiKeyFile;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
    }
}
