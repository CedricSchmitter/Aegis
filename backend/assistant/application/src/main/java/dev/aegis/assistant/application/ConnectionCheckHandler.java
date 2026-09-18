package dev.aegis.assistant.application;

import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.Workload;
import java.util.List;
import java.util.Map;

/**
 * Smallest useful run: one structured call without tools or household data. A success proves that the server-side
 * credentials work, that the configured model is available to the account and that it honors the output schema.
 */
public class ConnectionCheckHandler implements WorkloadHandler {
    static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("status", Map.of("type", "string", "enum", List.of("ok"))),
            "required", List.of("status"),
            "additionalProperties", false);

    @Override
    public Workload workload() {
        return Workload.CONNECTION_CHECK;
    }

    @Override
    public PreparedRun prepare(RunContext run) {
        return new PreparedRun("You are the connection check of the Aegis household application.",
                List.of(ModelMessage.user("Confirm that you can answer by returning the status ok.")), OUTPUT_SCHEMA);
    }

    @Override
    public ValidatedOutput validate(RunContext run, ModelTurn turn) {
        if (turn.structuredOutput() instanceof Map<?, ?> answer && answer.size() == 1
                && "ok".equals(answer.get("status"))) {
            return new ValidatedOutput(JsonText.write(answer), answer);
        }
        throw new InvalidModelOutputException("output.schemaMismatch",
                "the connection check answer does not match the required schema");
    }
}
