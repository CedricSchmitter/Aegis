package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * Conservative input-token estimate used for the cost check before a call. It assumes three characters per token
 * (German text and JSON tokenize denser than English prose) plus a fixed allowance for the provider's own tool and
 * output-format instructions. The reported usage after the call is authoritative.
 */
final class TokenEstimate {
    static final int CHARS_PER_TOKEN = 3;
    static final int PROVIDER_OVERHEAD = 600;

    private TokenEstimate() {
    }

    static long inputTokens(String system, List<ModelMessage> messages, List<ToolSpec> tools,
            Map<String, Object> outputSchema) {
        long chars = system.length();
        for (ModelMessage message : messages) {
            chars += message.characters() + 16;
        }
        for (ToolSpec tool : tools) {
            chars += tool.name().length() + tool.description().length() + JsonText.write(tool.inputSchema()).length();
        }
        if (outputSchema != null) {
            chars += JsonText.write(outputSchema).length();
        }
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + PROVIDER_OVERHEAD;
    }
}
