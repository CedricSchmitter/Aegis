package dev.aegis.assistant.application.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Visible audit step of a run; never contains prompts, document text or tool arguments. */
public record StepView(int seq, int attempt, String kind, String name, String outcome, long inputTokens,
        long outputTokens, BigDecimal cost, Instant at) {
}
