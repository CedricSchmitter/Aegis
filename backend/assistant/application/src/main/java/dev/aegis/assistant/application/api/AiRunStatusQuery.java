package dev.aegis.assistant.application.api;

import java.util.Collection;
import java.util.Map;

/**
 * Internal read of run states for contexts that show the progress of their own AI work (for example the analysis
 * state of a document). The calling context has already authorized its member; the view carries no audit steps.
 */
public interface AiRunStatusQuery {
    /** The most recently requested run per idempotency key; keys without a run are absent. */
    Map<String, RunView> latestByKeys(Collection<String> idempotencyKeys);
}
