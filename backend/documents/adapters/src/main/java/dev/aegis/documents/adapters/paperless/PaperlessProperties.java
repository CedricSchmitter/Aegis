package dev.aegis.documents.adapters.paperless;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the Paperless archive. Credentials come from the server-side environment only. */
@ConfigurationProperties(prefix = "aegis.paperless")
public record PaperlessProperties(String baseUrl, String username, String password, Duration connectTimeout,
        Duration readTimeout) {
    public PaperlessProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(60) : readTimeout;
    }
}
