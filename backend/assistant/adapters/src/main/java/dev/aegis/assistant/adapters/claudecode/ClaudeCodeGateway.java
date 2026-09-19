package dev.aegis.assistant.adapters.claudecode;

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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Claude through the owner's subscription (provider {@code claude-code}, ADR-0003): each model call is one headless
 * {@code claude -p} run of the unmodified official Claude Code CLI, executed by the host runner under the VM user who
 * logged in to it. Aegis never reads, copies or stores that login. The subscription is personal, so only runs requested
 * by the configured members may use it.
 *
 * <p>The request maps to the same model, system prompt, JSON schema, effort and output allowance that the API provider
 * would send. The CLI takes one prompt without tools, so workloads that need tools or multi-turn conversations are
 * refused before anything runs; they need the API provider.
 */
@Component
@EnableConfigurationProperties(ClaudeCodeProperties.class)
public class ClaudeCodeGateway implements ModelGateway {
    public static final String PROVIDER = "claude-code";
    private static final Pattern RESET_EPOCH = Pattern.compile("\\|(\\d{9,11})");
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(6);

    private final ClaudeCli cli;
    private final Set<String> members;
    private final int maxTurns;
    private final MemberDirectory directory;
    private final ObjectMapper json;

    @Autowired
    public ClaudeCodeGateway(ClaudeCodeProperties properties, MemberDirectory directory, ObjectMapper json) {
        this(new HostRunnerClient(Path.of(properties.runnerSocket()), json), properties, directory, json);
    }

    ClaudeCodeGateway(ClaudeCli cli, ClaudeCodeProperties properties, MemberDirectory directory, ObjectMapper json) {
        this.cli = cli;
        this.members = Set.copyOf(properties.members());
        this.maxTurns = properties.maxTurns();
        this.directory = directory;
        this.json = json;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return cli.available();
    }

    @Override
    public boolean allows(MemberId member) {
        return directory.findMember(member).filter(MemberSummary::active)
                .map(summary -> members.contains(summary.username().toLowerCase(Locale.ROOT))).orElse(false);
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        if (!request.tools().isEmpty()) {
            throw new ModelCallException(FailureCategory.INVALID_REQUEST, "provider.toolsUnsupported",
                    "the Claude Code provider runs without tools; tool workloads need the API provider");
        }
        ClaudeCli.Call call = new ClaudeCli.Call(request.model(), request.system(), singlePrompt(request.messages()),
                request.outputSchema(), request.effort(), maxTurns, request.maxOutputTokens(), request.timeout());
        return interpret(cli.run(call), request);
    }

    /** The CLI takes one prompt; earlier turns, tool calls or tool results cannot be expressed. */
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

    /** Reads the CLI's {@code --output-format json} result; CLI-reported errors become classified failures. */
    ModelTurn interpret(ClaudeCli.Output output, ModelRequest request) {
        Map<?, ?> answer = parse(output.stdout());
        if (answer == null) {
            if (output.exitCode() != 0) {
                throw classify(output.stderr() + " " + output.stdout());
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
        if (Boolean.TRUE.equals(answer.get("is_error")) || !"success".equals(subtype) || output.exitCode() != 0) {
            throw classify(text + " " + output.stderr());
        }
        Object structured = null;
        if (request.outputSchema() != null) {
            structured = answer.get("structured_output");
            if (structured == null) {
                structured = parse(text);
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
    static ModelCallException classify(String message) {
        String safe = message == null ? "" : message.strip();
        String lower = safe.toLowerCase(Locale.ROOT);
        String detail = "Claude Code CLI: " + (safe.length() > 240 ? safe.substring(0, 240) : safe);
        if (lower.contains("usage limit") || lower.contains("rate limit") || lower.contains("hit your limit")
                || lower.contains("429")) {
            return new ModelCallException(FailureCategory.RATE_LIMITED, "provider.rateLimited", detail,
                    retryAfter(safe), null);
        }
        if (lower.contains("login") || lower.contains("oauth") || lower.contains("401") || lower.contains("403")
                || lower.contains("authenticat") || lower.contains("api key") || lower.contains("unauthorized")) {
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
}
