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
     * Whether runs requested by {@code member} may use this provider. A personal credential, such as the owner's
     * Claude subscription, serves only its owner; a household API key serves every member. Checked before every run
     * and call, in addition to the household membership check.
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
