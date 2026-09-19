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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The Claude Code (subscription) adapter against a controlled CLI executor; no process, socket or login is used. */
class ClaudeCodeGatewayTest {
    static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("status", Map.of("type", "string")), "required", List.of("status"),
            "additionalProperties", false);
    static final String USAGE = "\"usage\":{\"input_tokens\":900,\"output_tokens\":12,\"cache_read_input_tokens\":0,"
            + "\"cache_creation_input_tokens\":0},\"modelUsage\":{\"claude-sonnet-5\":{}}";

    private final MemberId cedric = MemberId.random();
    private final MemberId lisa = MemberId.random();
    private final MemberId formerMember = MemberId.random();
    private final ScriptedCli cli = new ScriptedCli();
    private final ClaudeCodeGateway gateway = new ClaudeCodeGateway(cli,
            new ClaudeCodeProperties("/unused", List.of(" Cedric ", "former"), null), new Directory(),
            JsonMapper.builder().build());

    /** Answers every call with a fixed output and records the calls. */
    static final class ScriptedCli implements ClaudeCli {
        final List<Call> calls = new ArrayList<>();
        boolean available = true;
        Output output;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Output run(Call call) {
            calls.add(call);
            return output;
        }

        ScriptedCli answer(int exitCode, String stdout, String stderr) {
            output = new Output(exitCode, stdout, stderr);
            return this;
        }
    }

    final class Directory implements MemberDirectory {
        @Override
        public List<MemberSummary> listMembers(Actor actor) {
            return List.of();
        }

        @Override
        public Optional<MemberSummary> findMember(MemberId id) {
            if (id.equals(cedric)) {
                return Optional.of(new MemberSummary(id, "cedric", "Cedric", ProductLocale.DE, true));
            }
            if (id.equals(lisa)) {
                return Optional.of(new MemberSummary(id, "lisa", "Lisa", ProductLocale.DE, true));
            }
            if (id.equals(formerMember)) {
                return Optional.of(new MemberSummary(id, "former", "Former", ProductLocale.DE, false));
            }
            return Optional.empty();
        }
    }

    private static ModelRequest request(Map<String, Object> schema) {
        return new ModelRequest("claude-sonnet-5", "You classify household documents.",
                List.of(ModelMessage.user("Return ok.")), List.of(), schema, 2048, Duration.ofSeconds(30), "low");
    }

    private static String result(String fields) {
        return "{\"type\":\"result\"," + fields + "}";
    }

    @Test
    void theCallCarriesTheSameSettingsAsAnApiRequestAndReturnsTheStructuredAnswer() {
        cli.answer(0, result("\"subtype\":\"success\",\"is_error\":false,\"result\":\"{\\\"status\\\":\\\"ok\\\"}\","
                + "\"structured_output\":{\"status\":\"ok\"}," + USAGE), "");

        ModelTurn turn = gateway.complete(request(SCHEMA));

        assertThat(cli.calls).singleElement().satisfies(call -> {
            assertThat(call.model()).isEqualTo("claude-sonnet-5");
            assertThat(call.systemPrompt()).isEqualTo("You classify household documents.");
            assertThat(call.prompt()).isEqualTo("Return ok.");
            assertThat(call.jsonSchema()).isEqualTo(SCHEMA);
            assertThat(call.effort()).isEqualTo("low");
            assertThat(call.maxOutputTokens()).isEqualTo(2048);
            assertThat(call.maxTurns()).isEqualTo(3);
            assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(30));
        });
        assertThat(turn.stop()).isEqualTo(ModelTurn.Stop.END);
        assertThat(turn.structuredOutput()).isEqualTo(Map.of("status", "ok"));
        assertThat(turn.usage().inputTokens()).isEqualTo(900);
        assertThat(turn.usage().outputTokens()).isEqualTo(12);
        assertThat(turn.modelId()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void jsonInTheResultTextIsAcceptedWhenTheCliReportsNoStructuredOutput() {
        cli.answer(0, result("\"subtype\":\"success\",\"is_error\":false,\"result\":\"{\\\"status\\\":\\\"ok\\\"}\","
                + USAGE), "");

        assertThat(gateway.complete(request(SCHEMA)).structuredOutput()).isEqualTo(Map.of("status", "ok"));
    }

    @Test
    void cliFailuresAreClassified() {
        // Messages observed from Claude Code without a login and with an expired one.
        assertFailure(1, result("\"subtype\":\"success\",\"is_error\":true,\"result\":\"Not logged in · Please run "
                + "/login\""), "", FailureCategory.AUTHENTICATION, "provider.authentication");
        assertFailure(1, result("\"subtype\":\"success\",\"is_error\":true,\"result\":\"Failed to authenticate. API "
                + "Error: 401 OAuth access token is invalid.\""), "", FailureCategory.AUTHENTICATION,
                "provider.authentication");
        assertFailure(1, result("\"subtype\":\"success\",\"is_error\":true,\"result\":\"API Error: 404 model: "
                + "claude-x not found\""), "", FailureCategory.MODEL_UNAVAILABLE, "provider.modelNotFound");
        assertFailure(2, "", "Error: the runtime exploded", FailureCategory.PROVIDER_UNAVAILABLE,
                "provider.unavailable");
        assertFailure(0, "this is not json", "", FailureCategory.INVALID_OUTPUT, "provider.invalidResponse");
    }

    @Test
    void subscriptionLimitCarriesItsResetTime() {
        cli.answer(1, result("\"subtype\":\"success\",\"is_error\":true,\"result\":\"Claude AI usage limit reached|"
                + (Instant.now().getEpochSecond() + 3600) + "\""), "");

        assertThatThrownBy(() -> gateway.complete(request(SCHEMA)))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(FailureCategory.RATE_LIMITED);
                    assertThat(failure.retryAfter()).isBetween(Duration.ofMinutes(55), Duration.ofMinutes(61));
                });
    }

    @Test
    void turnLimitIsReportedAsAnUnfinishedAnswerWithItsUsage() {
        cli.answer(1, result("\"subtype\":\"error_max_turns\",\"is_error\":true,\"result\":\"\"," + USAGE), "");

        ModelTurn turn = gateway.complete(request(SCHEMA));

        assertThat(turn.stop()).isEqualTo(ModelTurn.Stop.OTHER);
        assertThat(turn.usage().outputTokens()).isEqualTo(12);
    }

    @Test
    void toolsAndMultiTurnConversationsAreRefusedBeforeTheCliRuns() {
        ToolSpec tool = new ToolSpec("search_documents", "Searches.", Map.of("type", "object"));
        ModelRequest withTools = new ModelRequest("claude-sonnet-5", "s", List.of(ModelMessage.user("x")),
                List.of(tool), null, 64, Duration.ofSeconds(5), null);
        ModelRequest multiTurn = new ModelRequest("claude-sonnet-5", "s", List.of(ModelMessage.user("x"),
                new ModelMessage(ModelMessage.Role.ASSISTANT, List.of(new ContentPart.Text("y")), null)), List.of(),
                null, 64, Duration.ofSeconds(5), null);

        assertThatThrownBy(() -> gateway.complete(withTools)).isInstanceOfSatisfying(ModelCallException.class,
                failure -> assertThat(failure.code()).isEqualTo("provider.toolsUnsupported"));
        assertThatThrownBy(() -> gateway.complete(multiTurn)).isInstanceOfSatisfying(ModelCallException.class,
                failure -> assertThat(failure.code()).isEqualTo("provider.conversationUnsupported"));
        assertThat(cli.calls).isEmpty();
    }

    @Test
    void configuredFollowsTheRunner() {
        assertThat(gateway.configured()).isTrue();
        cli.available = false;
        assertThat(gateway.configured()).isFalse();
    }

    @Test
    void onlyListedActiveMembersMayUseThePersonalSubscription() {
        assertThat(gateway.allows(cedric)).isTrue();
        assertThat(gateway.allows(lisa)).isFalse();
        assertThat(gateway.allows(formerMember)).as("inactive").isFalse();
        assertThat(gateway.allows(MemberId.random())).as("unknown").isFalse();
    }

    private void assertFailure(int exitCode, String stdout, String stderr, FailureCategory category, String code) {
        cli.answer(exitCode, stdout, stderr);
        assertThatThrownBy(() -> gateway.complete(request(SCHEMA)))
                .as(stdout + stderr).isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(category);
                    assertThat(failure.code()).isEqualTo(code);
                });
    }
}
