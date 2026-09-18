package dev.aegis.assistant.adapters.claudecode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelCallException;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import dev.aegis.assistant.domain.FailureCategory;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.ProductLocale;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The Claude Code (subscription) adapter against a controlled stand-in for the CLI. */
class ClaudeCodeGatewayTest {
    static final MemberSummary CEDRIC = new MemberSummary(MemberId.random(), "cedric", "Cedric", ProductLocale.DE,
            true);
    static final MemberSummary LISA = new MemberSummary(MemberId.random(), "lisa", "Lisa", ProductLocale.DE, true);
    static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("status", Map.of("type", "string")), "required", List.of("status"),
            "additionalProperties", false);

    private Path record;
    private Path tokenFile;
    private ClaudeCodeGateway gateway;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException, URISyntaxException {
        record = dir.resolve("record.txt");
        tokenFile = dir.resolve("claude-code-oauth-token");
        Files.writeString(tokenFile, FakeClaudeCli.EXPECTED_TOKEN + "\n");
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classes = Path.of(FakeClaudeCli.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        List<String> command = List.of(java, "-cp", classes, FakeClaudeCli.class.getName(), record.toString());
        MemberDirectory members = new MemberDirectory() {
            @Override
            public List<MemberSummary> listMembers(Actor actor) {
                return List.of(CEDRIC, LISA);
            }

            @Override
            public Optional<MemberSummary> findMember(MemberId id) {
                return List.of(CEDRIC, LISA).stream().filter(member -> member.id().equals(id)).findFirst();
            }
        };
        gateway = new ClaudeCodeGateway(new ClaudeCodeProperties(command, tokenFile.toString(), List.of(" Cedric "),
                null), members, JsonMapper.builder().build());
    }

    private static ModelRequest request(String model, Map<String, Object> schema) {
        return new ModelRequest(model, "You are the connection check.", List.of(ModelMessage.user("Return ok.")),
                List.of(), schema, 1024, Duration.ofSeconds(30), null);
    }

    private Map<String, String> recorded() throws IOException {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String line : Files.readString(record, StandardCharsets.UTF_8).split("\n")) {
            int split = line.indexOf('=');
            if (split > 0) {
                values.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return values;
    }

    @Test
    void subscriptionIsLimitedToTheListedMembers() {
        assertThat(gateway.allows(CEDRIC.id())).isTrue();
        assertThat(gateway.allows(LISA.id())).isFalse();
        assertThat(gateway.allows(MemberId.random())).isFalse();
    }

    @Test
    void cliRunsLockedDownWithOnlyTheTokenAndReturnsTheStructuredAnswer() throws IOException {
        ModelTurn turn = gateway.complete(request("ok", SCHEMA));

        assertThat(turn.stop()).isEqualTo(ModelTurn.Stop.END);
        assertThat(turn.structuredOutput()).isEqualTo(Map.of("status", "ok"));
        assertThat(turn.usage().inputTokens()).isEqualTo(900);
        assertThat(turn.modelId()).isEqualTo("claude-haiku-4-5-20251001");
        Map<String, String> seen = recorded();
        List<String> args = Arrays.asList(seen.get("args").split("<ARG>"));
        assertThat(args).containsSubsequence("-p", "--output-format", "json")
                .containsSubsequence("--disallowedTools", "*")
                .contains("--strict-mcp-config", "--disable-slash-commands", "--no-session-persistence",
                        "--json-schema")
                .containsSubsequence("--permission-mode", "dontAsk").containsSubsequence("--max-turns", "3")
                .doesNotContain("--bare", "--dangerously-skip-permissions", "--mcp-config");
        assertThat(seen.get("tokenOk")).isEqualTo("true");
        assertThat(seen.get("env")).doesNotContain("AEGIS_", "DB_PASSWORD", "ANTHROPIC_API_KEY");
        assertThat(seen.get("cwdWasEmpty")).isEqualTo("true");
        assertThat(Path.of(seen.get("home")).toAbsolutePath()).isEqualTo(Path.of(seen.get("cwd")));
        assertThat(Path.of(seen.get("cwd"))).doesNotExist();
        assertThat(seen.get("prompt")).isEqualTo("Return ok.");
    }

    @Test
    void jsonInTheResultTextIsAcceptedWhenTheCliReportsNoStructuredOutput() {
        ModelTurn turn = gateway.complete(request("text-json", SCHEMA));

        assertThat(turn.structuredOutput()).isEqualTo(Map.of("status", "ok"));
    }

    @Test
    void cliFailuresAreClassified() {
        assertFailure("login", FailureCategory.AUTHENTICATION, "provider.authentication");
        assertFailure("not-logged-in", FailureCategory.AUTHENTICATION, "provider.authentication");
        assertFailure("missing-model", FailureCategory.MODEL_UNAVAILABLE, "provider.modelNotFound");
        assertFailure("crash", FailureCategory.PROVIDER_UNAVAILABLE, "provider.unavailable");
        assertFailure("garbage", FailureCategory.INVALID_OUTPUT, "provider.invalidResponse");
    }

    @Test
    void subscriptionLimitCarriesItsResetTime() {
        assertThatThrownBy(() -> gateway.complete(request("limit", SCHEMA)))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(FailureCategory.RATE_LIMITED);
                    assertThat(failure.retryAfter()).isBetween(Duration.ofMinutes(55), Duration.ofMinutes(61));
                });
    }

    @Test
    void theTokenNeverAppearsInAFailureMessage() {
        assertThatThrownBy(() -> gateway.complete(request("echo-token", SCHEMA)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.getMessage()).doesNotContain(FakeClaudeCli.EXPECTED_TOKEN)
                                .contains("***"));
    }

    @Test
    void turnLimitIsReportedAsAnUnfinishedAnswerWithItsUsage() {
        ModelTurn turn = gateway.complete(request("max-turns", SCHEMA));

        assertThat(turn.stop()).isEqualTo(ModelTurn.Stop.OTHER);
        assertThat(turn.usage().outputTokens()).isEqualTo(12);
    }

    @Test
    void slowCliIsKilledWhenTheAttemptTimeIsUp() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> gateway.complete(new ModelRequest("slow", "s", List.of(ModelMessage.user("x")),
                List.of(), null, 64, Duration.ofSeconds(3), null)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void toolsAndMultiTurnConversationsAreRefusedBeforeAnyProcessStarts() throws IOException {
        ToolSpec tool = new ToolSpec("search_documents", "Searches.", Map.of("type", "object"));
        ModelRequest withTools = new ModelRequest("ok", "s", List.of(ModelMessage.user("x")), List.of(tool), null,
                64, Duration.ofSeconds(5), null);
        ModelRequest multiTurn = new ModelRequest("ok", "s", List.of(ModelMessage.user("x"),
                new ModelMessage(ModelMessage.Role.ASSISTANT, List.of(new ContentPart.Text("y")), null)), List.of(),
                null, 64, Duration.ofSeconds(5), null);

        assertThatThrownBy(() -> gateway.complete(withTools)).isInstanceOfSatisfying(ModelCallException.class,
                failure -> assertThat(failure.code()).isEqualTo("provider.toolsUnsupported"));
        assertThatThrownBy(() -> gateway.complete(multiTurn)).isInstanceOfSatisfying(ModelCallException.class,
                failure -> assertThat(failure.code()).isEqualTo("provider.conversationUnsupported"));
        assertThat(record).doesNotExist();
    }

    @Test
    void missingTokenMeansNotConfigured() throws IOException {
        Files.delete(tokenFile);

        assertThat(gateway.configured()).isFalse();
        assertThatThrownBy(() -> gateway.complete(request("ok", SCHEMA)))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.category()).isEqualTo(FailureCategory.NOT_CONFIGURED));
    }

    private void assertFailure(String model, FailureCategory category, String code) {
        assertThatThrownBy(() -> gateway.complete(request(model, SCHEMA)))
                .as(model).isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(category);
                    assertThat(failure.code()).isEqualTo(code);
                });
    }
}
