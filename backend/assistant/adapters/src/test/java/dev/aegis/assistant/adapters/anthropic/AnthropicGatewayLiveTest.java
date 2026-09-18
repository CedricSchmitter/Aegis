package dev.aegis.assistant.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.assistant.application.ports.ContentPart;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelRequest;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.application.ports.ToolSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * LIVE test against the real Claude API. It is skipped unless {@code AEGIS_AI_LIVE_TEST=true} and costs a few cents
 * per run. It reads the API key from {@code AEGIS_ANTHROPIC_API_KEY_FILE} (never from the repository) and checks
 * each model in {@code AEGIS_AI_LIVE_MODELS} (default: the configured classification and review models) for
 * authentication, availability, structured output and a tool round trip. It prints usage only, never content.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "AEGIS_AI_LIVE_TEST", matches = "true")
class AnthropicGatewayLiveTest {
    static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("status", Map.of("type", "string", "enum", List.of("ok"))),
            "required", List.of("status"), "additionalProperties", false);
    static final ToolSpec CLOCK = new ToolSpec("household_clock", "Returns the current household date.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));

    private final AnthropicModelGateway gateway = new AnthropicModelGateway(new AnthropicProperties(
            System.getenv("AEGIS_ANTHROPIC_API_KEY_FILE"), null, null), JsonMapper.builder().build());

    private static List<String> models() {
        String configured = System.getenv("AEGIS_AI_LIVE_MODELS");
        return List.of((configured == null || configured.isBlank() ? "claude-haiku-4-5,claude-opus-5" : configured)
                .split(","));
    }

    @Test
    void configuredModelsAnswerWithStructuredOutputAndUseTools() {
        assertThat(gateway.configured()).as("API key file configured").isTrue();
        try {
            for (String model : models()) {
                ModelTurn structured = gateway.complete(new ModelRequest(model.strip(),
                        "You are the connection check of a household application.",
                        List.of(ModelMessage.user("Return the status ok.")), List.of(), SCHEMA, 1024,
                        Duration.ofSeconds(90), null));
                assertThat(structured.stop()).as(model).isEqualTo(ModelTurn.Stop.END);
                assertThat(structured.structuredOutput()).as(model).isEqualTo(Map.of("status", "ok"));

                ModelTurn toolUse = gateway.complete(new ModelRequest(model.strip(),
                        "Use the household_clock tool before answering.",
                        List.of(ModelMessage.user("What is the household date? Use the tool.")), List.of(CLOCK), null,
                        1024, Duration.ofSeconds(90), null));
                assertThat(toolUse.stop()).as(model).isEqualTo(ModelTurn.Stop.TOOL_USE);
                ContentPart.ToolCall call = toolUse.toolCalls().getFirst();
                ModelTurn answer = gateway.complete(new ModelRequest(model.strip(),
                        "Use the household_clock tool before answering.",
                        List.of(ModelMessage.user("What is the household date? Use the tool."),
                                toolUse.asAssistantMessage(), ModelMessage.toolResults(List.of(
                                        new ContentPart.ToolResult(call.id(), "{\"date\":\"2026-09-18\"}", false)))),
                        List.of(CLOCK), null, 1024, Duration.ofSeconds(90), null));
                assertThat(answer.stop()).as(model).isEqualTo(ModelTurn.Stop.END);
                System.out.printf("LIVE %s -> %s: structured %s, tool round trip %s + %s%n", model,
                        structured.modelId(), structured.usage(), toolUse.usage(), answer.usage());
            }
        } finally {
            gateway.destroy();
        }
    }
}
