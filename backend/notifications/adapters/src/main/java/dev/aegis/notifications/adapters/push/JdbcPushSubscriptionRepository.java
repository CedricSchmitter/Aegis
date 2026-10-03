package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.domain.push.PushEndpoint;
import dev.aegis.notifications.domain.push.PushSubscription;
import dev.aegis.notifications.domain.push.PushSubscriptionId;
import dev.aegis.notifications.domain.push.PushSubscriptionRepository;
import dev.aegis.shared.MemberId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Subscribed browsers in {@code push_subscriptions}; the endpoint URL is unique. */
@Component
public class JdbcPushSubscriptionRepository implements PushSubscriptionRepository {
    private static final String SELECT = """
            SELECT id, member_id, endpoint, p256dh, auth, device_label, created_at, subscribed_at, last_sent_at,
                   consecutive_failures, expired_at
              FROM push_subscriptions""";

    private final JdbcClient jdbc;

    JdbcPushSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PushSubscription> findById(PushSubscriptionId id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id.value()).query(this::map).optional();
    }

    @Override
    public Optional<PushSubscription> findByEndpointUrl(String url) {
        return jdbc.sql(SELECT + " WHERE endpoint = ?").param(url).query(this::map).optional();
    }

    @Override
    public List<PushSubscription> findByMember(MemberId member) {
        return jdbc.sql(SELECT + " WHERE member_id = ? ORDER BY created_at, id").param(member.value())
                .query(this::map).list();
    }

    @Override
    public List<PushSubscription> findActive() {
        return jdbc.sql(SELECT + " WHERE expired_at IS NULL ORDER BY created_at, id").query(this::map).list();
    }

    @Override
    public void save(PushSubscription s) {
        jdbc.sql("""
                INSERT INTO push_subscriptions (id, member_id, endpoint, p256dh, auth, device_label, created_at,
                                                subscribed_at, last_sent_at, consecutive_failures, expired_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET member_id = EXCLUDED.member_id, endpoint = EXCLUDED.endpoint,
                    p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, device_label = EXCLUDED.device_label,
                    subscribed_at = EXCLUDED.subscribed_at, last_sent_at = EXCLUDED.last_sent_at,
                    consecutive_failures = EXCLUDED.consecutive_failures, expired_at = EXCLUDED.expired_at""")
                .params(s.id().value(), s.member().value(), s.endpoint().url(), s.endpoint().p256dh(),
                        s.endpoint().auth(), s.deviceLabel().orElse(null), ts(s.createdAt()), ts(s.subscribedAt()),
                        s.lastSentAt().map(Timestamp::from).orElse(null), s.consecutiveFailures(),
                        s.expiredAt().map(Timestamp::from).orElse(null))
                .update();
    }

    @Override
    public void delete(PushSubscriptionId id) {
        jdbc.sql("DELETE FROM push_subscriptions WHERE id = ?").param(id.value()).update();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private PushSubscription map(ResultSet row, int index) throws SQLException {
        return PushSubscription.rehydrate(new PushSubscriptionId(row.getObject("id", UUID.class)),
                new MemberId(row.getObject("member_id", UUID.class)),
                new PushEndpoint(row.getString("endpoint"), row.getString("p256dh"), row.getString("auth")),
                row.getString("device_label"), instant(row, "created_at"), instant(row, "subscribed_at"),
                instant(row, "last_sent_at"), row.getInt("consecutive_failures"), instant(row, "expired_at"));
    }
}
