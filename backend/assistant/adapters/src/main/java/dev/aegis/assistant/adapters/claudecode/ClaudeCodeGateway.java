package dev.aegis.assistant.adapters.claudecode;

import dev.aegis.assistant.adapters.secrets.SecretFile;
import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.assistant.domain.TokenUsage;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.shared.MemberId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude through the owner's subscription: runs the unmodified official Claude Code CLI in print mode with the
 * owner's {@code claude setup-token} OAuth token (ADR-0003). The subscription is personal, so only runs requested by
 * the configured members may use it.
 *
 * <p>Each call starts one CLI process with every built-in tool removed, no MCP servers, no skills or slash commands,
 * no session persistence, an empty temporary home and working directory and an environment that contains only the
 * token and switches for non-essential traffic; database passwords and other secrets of the application are not
 * inherited. The process is killed when the attempt's time is up. Multi-turn tool conversations are not supported;
 * workloads that need tools use the API provider.
 */
@Component
@EnableConfigurationProperties(ClaudeCodeProperties.class)
public class ClaudeCodeGateway implements ModelGateway {
    public static final String PROVIDER = "claude-code";
    static final String TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN";
    static final int MAX_STDOUT = 4 * 1024 * 1024;
    static final int MAX_STDERR = 64 * 1024;
    private static final Pattern RESET_EPOCH = Pattern.compile("\\|(\\d{9,11})");
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(6);

    private final List<String> command;
    private final SecretFile tokenFile;
    private final Set<String> allowedMembers;
    private final int maxTurns;
    private final MemberDirectory members;
    private final ObjectMapper json;

    public ClaudeCodeGateway(ClaudeCodeProperties properties, MemberDirectory members, ObjectMapper json) {
        this.command = properties.command();
        this.tokenFile = new SecretFile(Path.of(properties.tokenFile()));
        this.allowedMembers = Set.copyOf(properties.allowedMembers());
        this.maxTurns = properties.maxTurns();
        this.members = members;
        this.json = json;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return tokenFile.read().isPresent() && Files.isExecutable(Path.of(command.getFirst()));
    }

    @Override
    public boolean allows(MemberId member) {
        return members.findMember(member).filter(MemberSummary::active)
                .map(summary -> allowedMembers.contains(summary.username().toLowerCase(Locale.ROOT))).orElse(false);
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        if (!request.tools().isEmpty()) {
            throw new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.toolsUnsupported",
                    "the Claude Code provider runs without tools; tool workloads use the API provider");
        }
        String prompt = singlePrompt(request.messages());
        String token = tokenFile.read().orElseThrow(() -> new ModelCallException(FailureCategory.NOT_CONFIGURED,
                "ai.provider.notConfigured", "no readable Claude Code token file"));
        Path home = null;
        try {
            home = Files.createTempDirectory("aegis-claude-");
            Result result = run(arguments(request), prompt, token, home, request.timeout());
            return interpret(result, request, token);
        } catch (IOException failure) {
            throw new ModelCallException(FailureCategory.NOT_CONFIGURED, "provider.cliUnavailable",
                    "the Claude Code CLI could not be started: " + failure.getClass().getSimpleName());
        } finally {
            deleteQuietly(home);
        }
    }

    List<String> arguments(ModelRequest request) {
        List<String> args = new ArrayList<>(command);
        String system = request.outputSchema() == null ? request.system() : request.system()
                + "\n\nReply with exactly one JSON object that matches this JSON schema and nothing else:\n"
                + JsonText.write(request.outputSchema());
        args.addAll(List.of("-p", "--output-format", "json", "--model", request.model(),
                "--system-prompt", system,
                "--disallowedTools", "*", "--strict-mcp-config", "--disable-slash-commands",
                "--no-session-persistence", "--permission-mode", "dontAsk",
                "--max-turns", String.valueOf(maxTurns)));
        if (request.outputSchema() != null) {
            args.add("--json-schema");
            args.add(JsonText.write(request.outputSchema()));
        }
        if (request.effort() != null) {
            args.add("--effort");
            args.add(request.effort());
        }
        return args;
    }

    /** The provider takes one prompt; earlier turns, tool calls or tool results cannot be expressed. */
    private static String singlePrompt(List<ModelMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        for (ModelMessage message : messages) {
            if (message.role() != ModelMessage.Role.USER) {
                throw conversationUnsupported();
            }
            for (ContentPart part : message.parts()) {
                if (!(part instanceof ContentPart.Text text)) {
                    throw conversationUnsupported();
                }
                if (!prompt.isEmpty()) {
                    prompt.append("\n\n");
                }
                prompt.append(text.text());
            }
        }
        return prompt.toString();
    }

    private static ModelCallException conversationUnsupported() {
        return new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.conversationUnsupported",
                "the Claude Code provider accepts a single user prompt without tool turns");
    }

    record Result(int exitCode, String stdout, String stderr) {
    }

    private Result run(List<String> args, String prompt, String token, Path home, Duration timeout)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(args).directory(home.toFile());
        Map<String, String> environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        environment.clear();
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        environment.put("HOME", home.toString());
        environment.put("TMPDIR", home.toString());
        environment.put(TOKEN_ENV, token);
        environment.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        environment.put("DISABLE_AUTOUPDATER", "1");
        environment.put("CLAUDE_CODE_SKIP_PROMPT_HISTORY", "1");
        if (systemRoot != null) {
            // Only relevant when the tests run on Windows; the CLI itself runs on Linux.
            environment.put("SystemRoot", systemRoot);
        }
        Process process = builder.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream(),
                MAX_STDOUT));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream(),
                MAX_STDERR));
        try (OutputStream input = process.getOutputStream()) {
            input.write(prompt.getBytes(StandardCharsets.UTF_8));
        } catch (IOException closedEarly) {
            // The CLI may exit before reading its input (for example on a login error); its output explains why.
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new ModelCallException(FailureCategory.TIMEOUT, "provider.timeout",
                        "the Claude Code CLI did not finish within " + timeout.toSeconds() + " s and was stopped");
            }
            return new Result(process.exitValue(), new String(stdout.join(), StandardCharsets.UTF_8),
                    new String(stderr.join(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new ModelCallException(FailureCategory.INTERRUPTED, "attempt.interrupted",
                    "the call was interrupted");
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static byte[] readBounded(InputStream stream, int limit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (out.size() < limit) {
                    out.write(buffer, 0, Math.min(read, limit - out.size()));
                }
            }
        } catch (IOException ended) {
            // A killed process closes its streams; what was read so far is returned.
        }
        return out.toByteArray();
    }

    ModelTurn interpret(Result result, ModelRequest request, String token) {
        Map<?, ?> answer = parse(result.stdout());
        if (answer == null) {
            if (result.exitCode() != 0) {
                throw classify(result.stderr() + " " + result.stdout(), token);
            }
            throw new ModelCallException(FailureCategory.INVALID_OUTPUT, "provider.invalidResponse",
                    "the Claude Code CLI returned no readable result");
        }
        String subtype = String.valueOf(answer.get("subtype"));
        String text = answer.get("result") instanceof String value ? value : "";
        TokenUsage usage = usage(answer.get("usage"));
        String modelId = modelId(answer.get("modelUsage"), request.model());
        if ("error_max_turns".equals(subtype)) {
            return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.OTHER, usage, modelId, text,
                    null, null);
        }
        if (Boolean.TRUE.equals(answer.get("is_error")) || !"success".equals(subtype) || result.exitCode() != 0) {
            throw classify(text + " " + result.stderr(), token);
        }
        Object structured = null;
        if (request.outputSchema() != null) {
            structured = answer.get("structured_output");
            if (structured == null) {
                Map<?, ?> fromText = parse(text);
                structured = fromText;
            }
        }
        return new ModelTurn(List.of(new ContentPart.Text(text)), ModelTurn.Stop.END, usage, modelId, text,
                structured, null);
    }

    private Map<?, ?> parse(String stdout) {
        String trimmed = stdout.strip();
        int start = trimmed.lastIndexOf("\n{");
        String candidate = start >= 0 ? trimmed.substring(start + 1) : trimmed;
        try {
            Object value = json.readValue(candidate, Object.class);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (JacksonException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static TokenUsage usage(Object value) {
        if (!(value instanceof Map<?, ?> usage)) {
            return TokenUsage.NONE;
        }
        return new TokenUsage(count(usage.get("input_tokens")), count(usage.get("output_tokens")),
                count(usage.get("cache_read_input_tokens")), count(usage.get("cache_creation_input_tokens")));
    }

    private static long count(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static String modelId(Object modelUsage, String requested) {
        if (modelUsage instanceof Map<?, ?> models && models.size() == 1) {
            return String.valueOf(models.keySet().iterator().next());
        }
        return requested;
    }

    /** Classifies a CLI failure by its message; unknown failures count as a provider outage and are retried. */
    static ModelCallException classify(String message, String token) {
        String safe = message == null ? "" : message.replace(token, "***").strip();
        String lower = safe.toLowerCase(Locale.ROOT);
        String detail = "Claude Code CLI: " + (safe.length() > 240 ? safe.substring(0, 240) : safe);
        if (lower.contains("usage limit") || lower.contains("rate limit") || lower.contains("hit your limit")
                || lower.contains("429")) {
            return new ModelCallException(FailureCategory.RATE_LIMITED, "provider.rateLimited", detail,
                    retryAfter(safe), null);
        }
        if (lower.contains("login") || lower.contains("oauth") || lower.contains("401") || lower.contains("403")
                || lower.contains("authentication") || lower.contains("api key") || lower.contains("unauthorized")) {
            return new ModelCallException(FailureCategory.AUTHENTICATION, "provider.authentication", detail);
        }
        if (lower.contains("model") && (lower.contains("not found") || lower.contains("404")
                || lower.contains("not available") || lower.contains("selected model") || lower.contains("invalid"))) {
            return new ModelCallException(FailureCategory.MODEL_UNAVAILABLE, "provider.modelNotFound", detail);
        }
        if (lower.contains("400") || lower.contains("invalid_request")) {
            return new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.invalidRequest", detail);
        }
        return new ModelCallException(FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable", detail, null,
                null);
    }

    /** Subscription limits report their reset as {@code |<epoch seconds>}. */
    static Duration retryAfter(String message) {
        Matcher reset = RESET_EPOCH.matcher(message);
        if (!reset.find()) {
            return null;
        }
        Duration until = Duration.between(Instant.now(), Instant.ofEpochSecond(Long.parseLong(reset.group(1))));
        if (until.isNegative() || until.isZero()) {
            return null;
        }
        return until.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : until;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temporary file does not affect later calls.
                }
            });
        } catch (IOException ignored) {
            // nothing to clean up
        }
    }
}
