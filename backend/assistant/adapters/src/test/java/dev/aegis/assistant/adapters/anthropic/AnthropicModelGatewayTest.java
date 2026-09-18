package dev.aegis.assistant.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.FailureCategory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The Anthropic adapter against a local HTTP server with controlled Messages API responses. */
class AnthropicModelGatewayTest {
    static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("status", Map.of("type", "string")), "required", List.of("status"),
            "additionalProperties", false);
    static final String OK = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
             "content":[{"type":"text","text":"{\\"status\\":\\"ok\\"}"}],"stop_reason":"end_turn",
             "stop_sequence":null,"usage":{"input_tokens":321,"output_tokens":9,"cache_read_input_tokens":0,
             "cache_creation_input_tokens":0}}""";

    record Exchange(String method, String path, String apiKey, String version, String body) {
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final ConcurrentLinkedQueue<Exchange> exchanges = new ConcurrentLinkedQueue<>();
    private final List<Consumer<com.sun.net.httpserver.HttpExchange>> responses = new ArrayList<>();
    private HttpServer server;
    private Path keyFile;
    private AnthropicModelGateway gateway;

    @BeforeEach
    void start(@TempDir Path dir) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchanges.add(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    exchange.getRequestHeaders().getFirst("anthropic-version"), body));
            int index = exchanges.size() - 1;
            responses.get(Math.min(index, responses.size() - 1)).accept(exchange);
        });
        server.start();
        keyFile = dir.resolve("anthropic-api-key");
        Files.writeString(keyFile, "sk-ant-test-key-1\n");
        gateway = new AnthropicModelGateway(new AnthropicProperties(keyFile.toString(),
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2)), json);
    }

    @AfterEach
    void stop() {
        gateway.destroy();
        server.stop(0);
    }

    private void respond(int status, String body, String... headers) {
        responses.add(exchange -> {
            try {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    exchange.getResponseHeaders().add(headers[i], headers[i + 1]);
                }
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                exchange.close();
            }
        });
    }

    private static ModelRequest request(Map<String, Object> schema, List<ToolSpec> tools, Duration timeout) {
        return new ModelRequest("claude-haiku-4-5", "You are a test.", List.of(ModelMessage.user("Say ok.")), tools,
                schema, 256, timeout, null);
    }

    private static String error(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }

    @Test
    void structuredCallSendsOnlyTheRequestedFieldsAndParsesTheAnswer() throws Exception {
        respond(200, OK);

        ModelTurn turn = gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5)));

        assertThat(turn.stop()).isEqualTo(ModelTurn.Stop.END);
        assertThat(turn.structuredOutput()).isEqualTo(Map.of("status", "ok"));
        assertThat(turn.usage().inputTokens()).isEqualTo(321);
        assertThat(turn.usage().outputTokens()).isEqualTo(9);
        assertThat(turn.modelId()).isEqualTo("claude-haiku-4-5-20251001");
        Exchange exchange = exchanges.peek();
        assertThat(exchange.path()).isEqualTo("/v1/messages");
        assertThat(exchange.apiKey()).isEqualTo("sk-ant-test-key-1");
        assertThat(exchange.version()).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(exchange.body(), Map.class);
        assertThat(body).containsEntry("model", "claude-haiku-4-5").containsEntry("max_tokens", 256)
                .containsEntry("system", "You are a test.")
                .doesNotContainKeys("temperature", "top_p", "top_k", "thinking", "tools");
        assertThat(body.get("output_config")).isEqualTo(Map.of("format", Map.of("type", "json_schema",
                "schema", SCHEMA)));
    }

    @Test
    void toolCallsAreReadAndTheAssistantTurnIsEchoedUnchanged() throws Exception {
        respond(200, """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
                 "content":[{"type":"text","text":"Looking."},
                            {"type":"tool_use","id":"toolu_1","name":"search_documents","input":{"query":"Telekom"}}],
                 "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}""");
        respond(200, OK);
        ToolSpec search = new ToolSpec("search_documents", "Searches.", Map.of("type", "object",
                "properties", Map.of("query", Map.of("type", "string")), "required", List.of("query"),
                "additionalProperties", false));

        ModelTurn first = gateway.complete(request(null, List.of(search), Duration.ofSeconds(5)));
        List<ModelMessage> conversation = List.of(ModelMessage.user("Say ok."), first.asAssistantMessage(),
                ModelMessage.toolResults(List.of(new ContentPart.ToolResult("toolu_1", "{\"documents\":[]}", false))));
        gateway.complete(new ModelRequest("claude-haiku-4-5", "You are a test.", conversation, List.of(search), null,
                256, Duration.ofSeconds(5), null));

        assertThat(first.stop()).isEqualTo(ModelTurn.Stop.TOOL_USE);
        assertThat(first.toolCalls()).singleElement().isEqualTo(new ContentPart.ToolCall("toolu_1",
                "search_documents", Map.of("query", "Telekom")));
        List<Exchange> sent = new ArrayList<>(exchanges);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstBody = json.readValue(sent.get(0).body(), Map.class);
        assertThat(firstBody.get("tools").toString()).contains("search_documents").contains("additionalProperties");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondBody = json.readValue(sent.get(1).body(), Map.class);
        List<?> messages = (List<?>) secondBody.get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).toString()).contains("tool_use").contains("toolu_1").contains("Looking.");
        assertThat(messages.get(2).toString()).contains("tool_result").contains("toolu_1");
    }

    @Test
    void stopReasonsAreMapped() {
        assertThat(AnthropicMapping.stop(com.anthropic.models.messages.StopReason.REFUSAL))
                .isEqualTo(ModelTurn.Stop.REFUSAL);
        assertThat(AnthropicMapping.stop(com.anthropic.models.messages.StopReason.MAX_TOKENS))
                .isEqualTo(ModelTurn.Stop.MAX_TOKENS);
        assertThat(AnthropicMapping.stop(com.anthropic.models.messages.StopReason.PAUSE_TURN))
                .isEqualTo(ModelTurn.Stop.OTHER);
        assertThat(AnthropicMapping.stop(com.anthropic.models.messages.StopReason.of("something_new")))
                .isEqualTo(ModelTurn.Stop.OTHER);
    }

    @Test
    void invalidJsonAnswerIsReportedAsMissingStructuredOutput() {
        respond(200, OK.replace("{\\\"status\\\":\\\"ok\\\"}", "status: ok"));

        ModelTurn turn = gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5)));

        assertThat(turn.text()).isEqualTo("status: ok");
        assertThat(turn.structuredOutput()).isNull();
    }

    @Test
    void providerErrorsAreClassifiedWithoutHiddenRetries() {
        assertFailure(401, error("authentication_error", "invalid x-api-key"), FailureCategory.AUTHENTICATION,
                "provider.authentication");
        assertFailure(402, error("billing_error", "credit balance too low"), FailureCategory.AUTHENTICATION,
                "provider.billing");
        assertFailure(404, error("not_found_error", "model: claude-x"), FailureCategory.MODEL_UNAVAILABLE,
                "provider.modelNotFound");
        assertFailure(400, error("invalid_request_error", "bad"), FailureCategory.INVALID_REQUEST,
                "provider.invalidRequest");
        assertFailure(500, error("api_error", "oops"), FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable");
        assertFailure(529, error("overloaded_error", "busy"), FailureCategory.PROVIDER_UNAVAILABLE,
                "provider.overloaded");
    }

    private void assertFailure(int status, String body, FailureCategory category, String code) {
        responses.clear();
        exchanges.clear();
        respond(status, body);

        assertThatThrownBy(() -> gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(category);
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).contains("HTTP " + status).doesNotContain("sk-ant");
                });
        assertThat(exchanges).as("no hidden SDK retry").hasSize(1);
    }

    @Test
    void rateLimitCarriesTheRetryAfterHint() {
        respond(429, error("rate_limit_error", "slow down"), "retry-after", "7");

        assertThatThrownBy(() -> gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(FailureCategory.RATE_LIMITED);
                    assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
        assertThat(exchanges).hasSize(1);
    }

    @Test
    void slowProviderTimesOutWithinTheCallTimeout() {
        responses.add(exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        long started = System.nanoTime();

        assertThatThrownBy(() -> gateway.complete(request(SCHEMA, List.of(), Duration.ofMillis(400))))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void missingKeyMeansNotConfiguredAndNothingIsSent() throws IOException {
        Files.delete(keyFile);

        assertThat(gateway.configured()).isFalse();
        assertThatThrownBy(() -> gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.NOT_CONFIGURED));
        assertThat(exchanges).isEmpty();
    }

    @Test
    void blankOrMalformedKeyFileIsNotAKey() throws IOException {
        Files.writeString(keyFile, "  \n");
        assertThat(gateway.configured()).isFalse();
        Files.writeString(keyFile, "two words");
        assertThat(gateway.configured()).isFalse();
    }

    @Test
    void rotatedKeyIsUsedWithoutRestart() throws IOException {
        respond(200, OK);
        gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5)));
        Files.writeString(keyFile, "sk-ant-test-key-rotated");
        Files.setLastModifiedTime(keyFile, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()
                + 5_000));

        gateway.complete(request(SCHEMA, List.of(), Duration.ofSeconds(5)));

        assertThat(exchanges).extracting(Exchange::apiKey).containsExactly("sk-ant-test-key-1",
                "sk-ant-test-key-rotated");
    }

    @Test
    void onlyHttpsOrLoopbackEndpointsAreAccepted() {
        assertThat(AnthropicModelGateway.requireSafeBaseUrl("https://api.anthropic.com"))
                .isEqualTo("https://api.anthropic.com");
        assertThat(AnthropicModelGateway.requireSafeBaseUrl("http://127.0.0.1:9")).isEqualTo("http://127.0.0.1:9");
        assertThatThrownBy(() -> AnthropicModelGateway.requireSafeBaseUrl("http://api.anthropic.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AnthropicModelGateway.requireSafeBaseUrl("ftp://example.org"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryAfterAcceptsSecondsOnly() {
        assertThat(AnthropicModelGateway.retryAfter(List.of("12"))).isEqualTo(Duration.ofSeconds(12));
        assertThat(AnthropicModelGateway.retryAfter(List.of("Wed, 21 Oct 2026 07:28:00 GMT"))).isNull();
        assertThat(AnthropicModelGateway.retryAfter(List.of())).isNull();
    }
}
