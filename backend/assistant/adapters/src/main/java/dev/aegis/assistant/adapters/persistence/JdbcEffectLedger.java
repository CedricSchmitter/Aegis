package dev.aegis.assistant.adapters.persistence;

import dev.aegis.assistant.application.ports.EffectLedger;
import dev.aegis.assistant.domain.RunId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Effect keys as primary keys. The insert takes part in the completion transaction; if the effect fails, the claim
 * rolls back with it. A concurrent claim of the same key waits for the first transaction and then finds the key taken.
 */
@Component
public class JdbcEffectLedger implements EffectLedger {
    private final JdbcClient jdbc;

    JdbcEffectLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(String effectKey, RunId run, Instant at) {
        return jdbc.sql("""
                INSERT INTO ai_run_effects (effect_key, run_id, applied_at) VALUES (?, ?, ?)
                ON CONFLICT (effect_key) DO NOTHING""")
                .params(effectKey, run.value(), Timestamp.from(at)).update() == 1;
    }
}
