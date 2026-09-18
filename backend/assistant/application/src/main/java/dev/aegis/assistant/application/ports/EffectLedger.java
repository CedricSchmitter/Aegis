package dev.aegis.assistant.application.ports;

import dev.aegis.assistant.domain.RunId;
import java.time.Instant;

/**
 * Records which run applied which effect, inside the transaction that applies it. A second claim of the same key,
 * by a retry, a revived job or another run for the same source version, fails, so an effect is written at most once.
 */
public interface EffectLedger {
    /** Returns true when the key was free and is now owned by {@code run}. */
    boolean claim(String effectKey, RunId run, Instant at);
}
