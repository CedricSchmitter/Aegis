package dev.aegis.assistant.adapters.claudecode;

import dev.aegis.assistant.application.ports.ModelCallException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Executes one locked-down {@code claude -p} call. The executor, not the caller, decides the command-line flags, so a
 * caller can only choose the fields below and never enable tools, settings or MCP servers.
 */
interface ClaudeCli {
    /** Whether the executor can currently be reached. */
    boolean available();

    /**
     * Runs the CLI and returns its raw output, including CLI-reported errors.
     *
     * @throws ModelCallException when the call could not be executed (unreachable, busy, timeout, rejected)
     */
    Output run(Call call);

    /**
     * One call: {@code jsonSchema} is optional and requests structured output; {@code effort} is optional;
     * {@code maxOutputTokens} bounds each model response; {@code timeout} is the remaining attempt time.
     */
    record Call(String model, String systemPrompt, String prompt, Map<String, Object> jsonSchema, String effort,
            int maxTurns, int maxOutputTokens, Duration timeout) {
        public Call {
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(prompt, "prompt");
            Objects.requireNonNull(timeout, "timeout");
            jsonSchema = jsonSchema == null ? null : Map.copyOf(jsonSchema);
        }
    }

    /** Exit code and bounded standard output and error of the CLI process. */
    record Output(int exitCode, String stdout, String stderr) {
    }
}
