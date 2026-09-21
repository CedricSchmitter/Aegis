package dev.aegis.assistant.adapters.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.adapters.secrets.SecretFile;
import dev.aegis.assistant.domain.FailureCategory;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude Messages API through the official Java SDK with a Console API key. The SDK's own retries are disabled so
 * that the run budgets decide about every retry; each call has an explicit timeout; only the configured HTTPS
 * endpoint is contacted. Prompts, outputs and the key are never logged.
 */
@Component
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicModelGateway implements ModelGateway, DisposableBean {
    public static final String PROVIDER = "anthropic";

    private final SecretFile keyFile;
    private final String baseUrl;
    private final Duration connectTimeout;
    private final ObjectMapper json;
    private final Object clientLock = new Object();
    private String clientKey;
    private AnthropicClient client;

    public AnthropicModelGateway(AnthropicProperties properties, ObjectMapper json) {
        this.keyFile = new SecretFile(Path.of(properties.apiKeyFile()));
        this.baseUrl = requireSafeBaseUrl(properties.baseUrl());
        this.connectTimeout = properties.connectTimeout();
        this.json = json;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return keyFile.read().isPresent();
    }

    @Override
    public String credentialRevision() {
        return keyFile.revision();
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        String key = keyFile.read().orElseThrow(() -> new ModelCallException(FailureCategory.NOT_CONFIGURED,
                "ai.provider.notConfigured", "no readable Anthropic API key file"));
        Duration connect = connectTimeout.compareTo(request.timeout()) < 0 ? connectTimeout : request.timeout();
        RequestOptions options = RequestOptions.builder()
                .timeout(Timeout.builder().connect(connect).read(request.timeout()).write(request.timeout())
                        .request(request.timeout()).build())
                .build();
        Message message;
        try {
            message = client(key).messages().create(AnthropicMapping.toParams(request), options);
        } catch (AnthropicException failure) {
            throw classify(failure);
        }
        return AnthropicMapping.toTurn(message, request.outputSchema() != null, json);
    }

    /** Maps SDK failures to failure categories; messages carry status and error type, never request content. */
    static ModelCallException classify(AnthropicException failure) {
        if (failure instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            String detail = "Anthropic API returned HTTP " + status + errorDetail(service.body());
            Duration retryAfter = retryAfter(service.headers().values("retry-after"));
            return switch (status) {
                case 401 -> new ModelCallException(FailureCategory.AUTHENTICATION, "provider.authentication", detail);
                case 402 -> new ModelCallException(FailureCategory.AUTHENTICATION, "provider.billing", detail);
                case 403 -> new ModelCallException(FailureCategory.AUTHENTICATION, "provider.permission", detail);
                case 404 -> new ModelCallException(FailureCategory.MODEL_UNAVAILABLE, "provider.modelNotFound",
                        detail);
                case 408, 409 -> new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE,
                        "provider.unavailable", detail, retryAfter, null);
                case 413 -> new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.requestTooLarge",
                        detail);
                case 429 -> new ModelCallException(FailureCategory.RATE_LIMITED, "provider.rateLimited", detail,
                        retryAfter, null);
                case 529 -> new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.overloaded",
                        detail, retryAfter, null);
                default -> status >= 500
                        ? new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable", detail,
                                retryAfter, null)
                        : new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.invalidRequest", detail);
            };
        }
        if (failure instanceof AnthropicIoException) {
            return timedOut(failure)
                    ? new ModelCallException(FailureCategory.TIMEOUT, "provider.timeout",
                            "the Anthropic API did not answer within the call timeout")
                    : new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unreachable",
                            "the Anthropic API could not be reached: " + failure.getClass().getSimpleName());
        }
        if (failure instanceof AnthropicInvalidDataException) {
            return new ModelCallException(FailureCategory.INVALID_OUTPUT, "provider.invalidResponse",
                    "the Anthropic API answered with data the client could not read");
        }
        return new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.error",
                "Anthropic client failure: " + failure.getClass().getSimpleName());
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static String errorDetail(JsonValue body) {
        try {
            Object converted = body.convert(Object.class);
            if (converted instanceof Map<?, ?> map && map.get("error") instanceof Map<?, ?> error) {
                String type = String.valueOf(error.get("type"));
                String message = String.valueOf(error.get("message"));
                message = message.length() > 300 ? message.substring(0, 300) : message;
                return " (" + type + "): " + message;
            }
        } catch (RuntimeException unreadable) {
            // The status code alone is still a useful diagnostic.
        }
        return "";
    }

    static Duration retryAfter(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(values.getFirst().strip());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }

    private AnthropicClient client(String key) {
        synchronized (clientLock) {
            if (client == null || !Objects.equals(clientKey, key)) {
                if (client != null) {
                    client.close();
                }
                client = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).maxRetries(0)
                        .timeout(Duration.ofMinutes(10)).build();
                clientKey = key;
            }
            return client;
        }
    }

    /** Only HTTPS endpoints are allowed; plain HTTP only for loopback test servers. */
    static String requireSafeBaseUrl(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException("invalid Anthropic base URL");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return baseUrl;
        }
        try {
            if ("http".equalsIgnoreCase(uri.getScheme()) && InetAddress.getByName(host).isLoopbackAddress()) {
                return baseUrl;
            }
        } catch (UnknownHostException unknown) {
            // falls through to the refusal
        }
        throw new IllegalStateException("the Anthropic base URL must use HTTPS");
    }

    @Override
    public void destroy() {
        synchronized (clientLock) {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }
}
