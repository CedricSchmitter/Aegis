package dev.aegis.assistant.adapters.claudecode;

import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.domain.FailureCategory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Client of the host runner ({@code ops/claude-runner}, protocol 1). The runner runs as the VM user who logged in to
 * Claude Code and executes {@code claude -p} with a fixed, locked-down set of flags; the login never leaves that user.
 * The application only sends the call fields as one JSON line over a Unix socket and receives one JSON line with the
 * CLI's exit code and output, or a runner status (timeout, busy, rejected, CLI unavailable).
 *
 * <p>Closing the connection makes the runner stop the CLI, so an interrupted or overdue attempt does not keep running
 * on the host.
 */
final class HostRunnerClient implements ClaudeCli {
    static final int PROTOCOL = 1;
    static final int MAX_RESPONSE = 12 * 1024 * 1024;
    /** Time the runner may need beyond the CLI timeout to stop the process and answer. */
    static final Duration GRACE = Duration.ofSeconds(10);

    private final Path socket;
    private final ObjectMapper json;
    private final Duration grace;

    HostRunnerClient(Path socket, ObjectMapper json) {
        this(socket, json, GRACE);
    }

    HostRunnerClient(Path socket, ObjectMapper json, Duration grace) {
        this.socket = socket;
        this.json = json;
        this.grace = grace;
    }

    @Override
    public boolean available() {
        return Files.exists(socket);
    }

    @Override
    public Output run(Call call) {
        byte[] request = request(call);
        AtomicBoolean finished = new AtomicBoolean();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            CompletableFuture.delayedExecutor(call.timeout().plus(grace).toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (finished.compareAndSet(false, true)) {
                            closeQuietly(channel);
                        }
                    });
            channel.connect(UnixDomainSocketAddress.of(socket));
            ByteBuffer out = ByteBuffer.wrap(request);
            while (out.hasRemaining()) {
                channel.write(out);
            }
            byte[] response = readAll(channel);
            if (!finished.compareAndSet(false, true)) {
                throw overdue(call.timeout().plus(grace));
            }
            return interpret(response);
        } catch (ClosedByInterruptException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelCallException(FailureCategory.INTERRUPTED, "attempt.interrupted",
                    "the call was interrupted");
        } catch (AsynchronousCloseException closed) {
            throw overdue(call.timeout().plus(grace));
        } catch (IOException unreachable) {
            if (!finished.compareAndSet(false, true)) {
                throw overdue(call.timeout().plus(grace));
            }
            throw new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unreachable",
                    "the Claude Code host runner could not be reached: " + unreachable.getClass().getSimpleName());
        } finally {
            finished.set(true);
        }
    }

    byte[] request(Call call) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("protocol", PROTOCOL);
        fields.put("model", call.model());
        fields.put("systemPrompt", call.systemPrompt());
        fields.put("prompt", call.prompt());
        fields.put("jsonSchema", call.jsonSchema());
        fields.put("effort", call.effort());
        fields.put("maxTurns", call.maxTurns());
        fields.put("maxOutputTokens", call.maxOutputTokens());
        fields.put("timeoutSeconds", Math.max(1, (call.timeout().toMillis() + 999) / 1000));
        // Serialized JSON contains no raw line breaks, so one line is one request.
        return (json.writeValueAsString(fields) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readAll(SocketChannel channel) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            if (response.size() + buffer.remaining() > MAX_RESPONSE) {
                throw new ModelCallException(FailureCategory.INVALID_OUTPUT, "provider.invalidResponse",
                        "the Claude Code host runner answered with more than " + MAX_RESPONSE + " bytes");
            }
            response.write(buffer.array(), 0, buffer.remaining());
            buffer.clear();
        }
        return response.toByteArray();
    }

    Output interpret(byte[] response) {
        Map<?, ?> answer;
        try {
            Object value = json.readValue(new String(response, StandardCharsets.UTF_8).strip(), Object.class);
            answer = value instanceof Map<?, ?> map ? map : Map.of();
        } catch (JacksonException | IllegalArgumentException unreadable) {
            answer = Map.of();
        }
        if (!(answer.get("protocol") instanceof Number protocol) || protocol.intValue() != PROTOCOL) {
            throw new ModelCallException(FailureCategory.INVALID_OUTPUT, "provider.invalidResponse",
                    "the Claude Code host runner answered without a readable protocol " + PROTOCOL + " response");
        }
        String message = "Claude Code host runner: " + text(answer.get("message"), 240);
        return switch (String.valueOf(answer.get("status"))) {
            case "completed" -> new Output(answer.get("exitCode") instanceof Number exit ? exit.intValue() : -1,
                    text(answer.get("stdout"), Integer.MAX_VALUE), text(answer.get("stderr"), Integer.MAX_VALUE));
            case "timeout" -> throw new ModelCallException(FailureCategory.TIMEOUT, "provider.timeout", message);
            case "busy" -> throw new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.busy",
                    message);
            case "rejected" -> throw new ModelCallException(FailureCategory.INVALID_REQUEST,
                    "provider.invalidRequest", message);
            case "cli_unavailable" -> throw new ModelCallException(FailureCategory.NOT_CONFIGURED,
                    "provider.cliUnavailable", message);
            default -> throw new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable",
                    message);
        };
    }

    private static String text(Object value, int limit) {
        String text = value instanceof String string ? string : "";
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private static ModelCallException overdue(Duration deadline) {
        return new ModelCallException(FailureCategory.TIMEOUT, "provider.timeout",
                "the Claude Code host runner did not answer within " + deadline.toSeconds() + " s");
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // The reader fails with an asynchronous close either way.
        }
    }
}
