package dev.aegis.assistant.application.ports;

import dev.aegis.shared.MemberId;

/**
 * Provider-neutral access to a language model. Implementations translate the request into the provider protocol,
 * perform exactly one call without hidden retries and report the provider's usage. They never execute tools, never
 * reach other hosts than the configured provider endpoint and never log prompts or outputs.
 */
public interface ModelGateway {
    /** Stable provider name used in workload profiles, for example {@code anthropic}. */
    String provider();

    /** Whether server-side credentials are present; checked before every run and call. */
    boolean configured();

    /**
     * Whether runs requested by this member may use the provider. A personal subscription, for example, may only be
     * used for its owner's own work; checked when a run is requested and again before every call.
     */
    default boolean allows(MemberId member) {
        return true;
    }

    /**
     * Performs one model call.
     *
     * @throws ModelCallException with a failure category for every provider or transport failure
     */
    ModelTurn complete(ModelRequest request);
}
