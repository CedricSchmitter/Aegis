package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.RunId;
import dev.aegis.shared.Actor;
import java.util.Map;
import java.util.Objects;

/**
 * A tool the model may call during a run. Implementations are thin adapters over public application contracts: they
 * pass the run's actor so that the same household authorization applies as for every other entry point, they are
 * read-only, and they validate the untrusted input themselves. Writes never happen through tools; a workload applies
 * its result deterministically after validation.
 *
 * <p>{@link dev.aegis.shared.NotAuthorizedException} propagates and fails the run; other expected failures are
 * returned as error results so that the model can react.
 */
public interface AiTool {

    record Invocation(RunId runId, Actor actor) {
        public Invocation {
            Objects.requireNonNull(runId, "run id");
            Objects.requireNonNull(actor, "actor");
        }
    }

    /** Result text for the model (usually JSON); {@code error} marks a failed call. */
    record Result(String content, boolean error) {
        public Result {
            Objects.requireNonNull(content, "content");
        }

        public static Result ok(String content) {
            return new Result(content, false);
        }

        public static Result error(String content) {
            return new Result(content, true);
        }
    }

    ToolSpec spec();

    Result invoke(Invocation invocation, Map<String, Object> input);
}
