package dev.aegis.assistant.adapters.claudecode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.domain.FailureCategory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The host runner client against a stand-in runner on a real Unix socket. */
class HostRunnerClientTest {
    private static final Duration GRACE = Duration.ofMillis(500);

    @TempDir
    Path dir;
    private final JsonMapper json = JsonMapper.builder().build();
    private ServerSocketChannel server;
    private final CompletableFuture<String> received = new CompletableFuture<>();

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    /** Accepts one connection, reads the request line and answers with {@code respond(request)}, or never. */
    private Path runner(Function<String, String> respond) throws IOException {
        Path socket = dir.resolve("runner.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofVirtual().start(() -> {
            try (SocketChannel connection = server.accept()) {
                String request = readLine(connection);
                received.complete(request);
                String response = respond.apply(request);
                if (response == null) {
                    // Keeps the connection open without answering until the client gives up.
                    connection.read(ByteBuffer.allocate(1));
                    return;
                }
                connection.write(ByteBuffer.wrap((response + "\n").getBytes(StandardCharsets.UTF_8)));
            } catch (IOException closed) {
                // The test ended or the client closed the connection.
            }
        });
        return socket;
    }

    private static String readLine(SocketChannel connection) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1);
        while (connection.read(one) > 0) {
            if (one.get(0) == '\n') {
                break;
            }
            line.write(one.get(0));
            one.clear();
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static ClaudeCli.Call call(Duration timeout) {
        return new ClaudeCli.Call("claude-sonnet-5", "You classify.", "Classify this.", Map.of("type", "object"),
                "low", 3, 2048, timeout);
    }

    @Test
    void sendsTheCallFieldsAsOneLineAndReturnsTheCliOutput() throws Exception {
        Path socket = runner(request -> "{\"protocol\":1,\"status\":\"completed\",\"exitCode\":0,"
                + "\"stdout\":\"{\\\"result\\\":\\\"ok\\\"}\",\"stderr\":\"\"}");
        HostRunnerClient client = new HostRunnerClient(socket, json, GRACE);

        ClaudeCli.Output output = client.run(call(Duration.ofMillis(29_500)));

        assertThat(output).isEqualTo(new ClaudeCli.Output(0, "{\"result\":\"ok\"}", ""));
        Map<String, Object> request = json.readValue(received.get(5, TimeUnit.SECONDS),
                new TypeReference<Map<String, Object>>() { });
        assertThat(request).containsOnlyKeys("protocol", "model", "systemPrompt", "prompt", "jsonSchema", "effort",
                "maxTurns", "maxOutputTokens", "timeoutSeconds");
        assertThat(request.get("protocol")).isEqualTo(1);
        assertThat(request.get("model")).isEqualTo("claude-sonnet-5");
        assertThat(request.get("systemPrompt")).isEqualTo("You classify.");
        assertThat(request.get("prompt")).isEqualTo("Classify this.");
        assertThat(request.get("jsonSchema")).isEqualTo(Map.of("type", "object"));
        assertThat(request.get("effort")).isEqualTo("low");
        assertThat(request.get("maxOutputTokens")).isEqualTo(2048);
        assertThat(request.get("timeoutSeconds")).as("rounded up").isEqualTo(30);
    }

    @Test
    void runnerStatusesAreClassified() throws Exception {
        for (var expected : List.of(
                List.of("timeout", FailureCategory.TIMEOUT, "provider.timeout"),
                List.of("busy", FailureCategory.PROVIDER_UNAVAILABLE, "provider.busy"),
                List.of("rejected", FailureCategory.INVALID_REQUEST, "provider.invalidRequest"),
                List.of("cli_unavailable", FailureCategory.NOT_CONFIGURED, "provider.cliUnavailable"),
                List.of("failed", FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable"))) {
            stop();
            Path socket = runner(request -> "{\"protocol\":1,\"status\":\"" + expected.get(0)
                    + "\",\"message\":\"why\"}");
            assertThatThrownBy(() -> new HostRunnerClient(socket, json, GRACE).run(call(Duration.ofSeconds(5))))
                    .as(expected.get(0).toString()).isInstanceOfSatisfying(ModelCallException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(expected.get(1));
                        assertThat(failure.code()).isEqualTo(expected.get(2));
                    });
            Files.deleteIfExists(socket);
        }
    }

    @Test
    void anUnreadableAnswerIsInvalidOutput() throws Exception {
        Path socket = runner(request -> "not json");

        assertThatThrownBy(() -> new HostRunnerClient(socket, json, GRACE).run(call(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.INVALID_OUTPUT));
    }

    @Test
    void aRunnerThatDoesNotAnswerInTimeIsGivenUp() throws Exception {
        Path socket = runner(request -> null);
        long started = System.nanoTime();

        assertThatThrownBy(() -> new HostRunnerClient(socket, json, GRACE).run(call(Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aMissingRunnerIsNotConfiguredAndUnreachable() {
        HostRunnerClient client = new HostRunnerClient(dir.resolve("missing.sock"), json, GRACE);

        assertThat(client.available()).isFalse();
        assertThatThrownBy(() -> client.run(call(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(FailureCategory.PROVIDER_UNAVAILABLE);
                    assertThat(failure.code()).isEqualTo("provider.unreachable");
                });
    }
}
