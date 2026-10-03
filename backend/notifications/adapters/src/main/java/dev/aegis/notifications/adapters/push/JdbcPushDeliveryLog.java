package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.domain.NoticeId;
import dev.aegis.notifications.domain.push.PushDeliveryLog;
import dev.aegis.notifications.domain.push.PushDeliveryState;
import dev.aegis.notifications.domain.push.PushSubscriptionId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** One row per notice and subscription in {@code push_deliveries}, holding the latest attempt. */
@Component
public class JdbcPushDeliveryLog implements PushDeliveryLog {
    private final JdbcClient jdbc;

    JdbcPushDeliveryLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<NoticeId, Attempt> attempts(PushSubscriptionId subscription, Collection<NoticeId> notices) {
        Map<NoticeId, Attempt> attempts = new HashMap<>();
        if (notices.isEmpty()) {
            return attempts;
        }
        jdbc.sql("""
                SELECT notice_id, state, attempts, last_attempt_at FROM push_deliveries
                 WHERE subscription_id = :subscription AND notice_id IN (:notices)""")
                .param("subscription", subscription.value())
                .param("notices", notices.stream().map(NoticeId::value).toList())
                .query((row, index) -> {
                    attempts.put(new NoticeId(row.getObject("notice_id", UUID.class)),
                            new Attempt(PushDeliveryState.valueOf(row.getString("state")), row.getInt("attempts"),
                                    row.getTimestamp("last_attempt_at").toInstant()));
                    return null;
                }).list();
        return attempts;
    }

    @Override
    public void record(PushSubscriptionId subscription, Collection<NoticeId> notices, PushDeliveryState state,
            Integer statusCode, Instant at) {
        for (NoticeId notice : notices) {
            jdbc.sql("""
                    INSERT INTO push_deliveries (notice_id, subscription_id, state, attempts, last_attempt_at,
                                                 status_code)
                    VALUES (?, ?, ?, 1, ?, ?)
                    ON CONFLICT (subscription_id, notice_id) DO UPDATE SET state = EXCLUDED.state,
                        attempts = push_deliveries.attempts + 1, last_attempt_at = EXCLUDED.last_attempt_at,
                        status_code = EXCLUDED.status_code""")
                    .params(notice.value(), subscription.value(), state.name(), Timestamp.from(at), statusCode)
                    .update();
        }
    }
}
