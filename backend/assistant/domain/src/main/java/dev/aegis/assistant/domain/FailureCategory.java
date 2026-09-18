package dev.aegis.assistant.domain;

/**
 * Machine-readable reason why an attempt or run failed. Retryable categories are retried automatically within the
 * attempt and cost budgets; all others end the run and need an explicit member decision.
 */
public enum FailureCategory {
    /** No credentials, missing handler or unknown model price. */
    NOT_CONFIGURED(false),
    /** The provider rejected the credentials or the account (401, 402, 403). */
    AUTHENTICATION(false),
    /** The configured model does not exist or is not available to the account. */
    MODEL_UNAVAILABLE(false),
    /** The provider rejected the request itself. */
    INVALID_REQUEST(false),
    RATE_LIMITED(true),
    /** Provider outage, overload or unreachable network. */
    PROVIDER_UNAVAILABLE(true),
    TIMEOUT(true),
    /** Malformed, truncated or schema-violating model output. */
    INVALID_OUTPUT(true),
    /** The model declined the request. */
    REFUSED(false),
    /** Cost, daily, time, model-call or tool-call budget reached. */
    BUDGET_EXHAUSTED(false),
    /** The model repeatedly asked for tools that the workload does not grant. */
    TOOL_DENIED(false),
    /** The requesting member is no longer allowed to run the use case. */
    NOT_AUTHORIZED(false),
    /** A source changed or vanished before the result could be applied. */
    STALE_SOURCE(false),
    /** A source system needed for the input is temporarily unreachable. */
    SOURCE_UNAVAILABLE(true),
    /** The process stopped while the attempt was running (restart, crash). */
    INTERRUPTED(true),
    /** Unexpected defect; retried within the attempt budget and logged. */
    INTERNAL(true);

    private final boolean retryable;

    FailureCategory(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
