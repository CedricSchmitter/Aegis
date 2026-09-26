package dev.aegis.operations.application.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One line of the status overview that answers "is everything OK?" (issue #83, F4) for one area such as
 * {@code database}, {@code archive}, {@code backup}, {@code storage}, {@code ai} or {@code jobs}. {@code state} is OK,
 * UNCHECKED (set up, but not verified since the application started; neutral, not a problem) or PROBLEM.
 * {@code reason} is a stable English code such as {@code backup.failed} and {@code params} its stable values; the web
 * app turns both into localized words. Neither ever carries backend messages, command output or household content.
 * {@code lastSuccessAt} is when the area last worked, if known.
 */
public record HealthCheck(String area, String state, String reason, Map<String, String> params,
        Instant lastSuccessAt) {
    public static final String OK = "OK";
    public static final String UNCHECKED = "UNCHECKED";
    public static final String PROBLEM = "PROBLEM";

    public HealthCheck {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        params = Map.copyOf(params);
    }

    public static HealthCheck ok(String area, String reason, Instant lastSuccessAt) {
        return new HealthCheck(area, OK, reason, Map.of(), lastSuccessAt);
    }

    public static HealthCheck problem(String area, String reason, Map<String, String> params, Instant lastSuccessAt) {
        return new HealthCheck(area, PROBLEM, reason, params, lastSuccessAt);
    }
}
