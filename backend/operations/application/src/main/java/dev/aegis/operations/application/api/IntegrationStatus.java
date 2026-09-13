package dev.aegis.operations.application.api;

import java.time.Instant;

/**
 * Observed state of one integration or service. {@code state} is CONNECTED, DEGRADED, UNAVAILABLE or
 * NOT_CONFIGURED; unconfigured services are reported as such instead of being simulated.
 */
public record IntegrationStatus(String name, String state, String detail, Instant lastSuccessAt, Instant checkedAt) {
    public static final String CONNECTED = "CONNECTED";
    public static final String DEGRADED = "DEGRADED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    public static IntegrationStatus notConfigured(String name, String detail) {
        return new IntegrationStatus(name, NOT_CONFIGURED, detail, null, null);
    }
}
