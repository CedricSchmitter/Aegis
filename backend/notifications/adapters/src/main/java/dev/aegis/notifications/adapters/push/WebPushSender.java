package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.application.push.PushMessage;
import dev.aegis.notifications.application.push.PushSender;
import dev.aegis.notifications.domain.push.PushEndpoint;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends Web Push messages (RFC 8030) to the browser's push service, for example Firebase Cloud Messaging for Chrome
 * and Samsung Internet on Android. The payload is encrypted for the browser (RFC 8291) and authorized with the
 * server's VAPID key (RFC 8292). Logs carry the push service host and status only, never texts or endpoints.
 */
@Component
public class WebPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final JdbcPushServerKey keys;
    private final ObjectMapper mapper;
    private final String subject;
    private final Clock clock;
    private final HttpClient http;
    private final Duration timeout;
    private final WebPushEncryption encryption = new WebPushEncryption(new SecureRandom());

    WebPushSender(JdbcPushServerKey keys, ObjectMapper mapper, Clock clock,
            @Value("${aegis.push.subject:mailto:aegis@aegis.invalid}") String subject,
            @Value("${aegis.push.timeout:15s}") Duration timeout) {
        if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
            throw new IllegalArgumentException("aegis.push.subject must be a mailto: or https: URI");
        }
        this.keys = keys;
        this.mapper = mapper;
        this.subject = subject;
        this.clock = clock;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Result send(PushEndpoint endpoint, PushMessage message) {
        JdbcPushServerKey.Pair pair;
        try {
            pair = keys.pair();
        } catch (RuntimeException unavailable) {
            log.warn("Push not sent: server key unavailable ({})", unavailable.getClass().getSimpleName());
            return new Result(Outcome.NOT_CONFIGURED, null);
        }
        URI uri = URI.create(endpoint.url());
        byte[] body = encryption.encrypt(payload(message), endpoint.publicKey(), endpoint.authSecret());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", Long.toString(message.ttl().toSeconds()))
                .header("Urgency", "normal")
                .header("Authorization", VapidSigner.authorization(uri, subject, pair.privateKey(),
                        pair.publicKey(), clock.instant()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (message.topic() != null) {
            request.header("Topic", message.topic());
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            Outcome outcome = classify(response.statusCode());
            if (outcome != Outcome.ACCEPTED) {
                log.info("Push to {} answered {}", endpoint.host(), response.statusCode());
            }
            return new Result(outcome, response.statusCode());
        } catch (IOException e) {
            log.info("Push to {} failed: {}", endpoint.host(), e.getClass().getSimpleName());
            return new Result(Outcome.UNAVAILABLE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.UNAVAILABLE, null);
        }
    }

    static Outcome classify(int status) {
        if (status >= 200 && status < 300) {
            return Outcome.ACCEPTED;
        }
        if (status == 404 || status == 410) {
            return Outcome.GONE;
        }
        if (status == 429 || status >= 500) {
            return Outcome.UNAVAILABLE;
        }
        return Outcome.REJECTED;
    }

    /** What the service worker reads: keep in sync with {@code frontend/public/sw.js}. */
    private byte[] payload(PushMessage message) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", message.title());
        payload.put("body", message.body());
        payload.put("path", message.path());
        payload.put("tag", message.tag());
        byte[] json = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        if (json.length > WebPushEncryption.MAX_PLAINTEXT) {
            // A very long task title must not block the push: the body is shortened to fit one record.
            payload.put("body", message.body().substring(0, Math.min(message.body().length(), 200)) + "…");
            json = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        }
        return json;
    }
}
