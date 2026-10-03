package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.domain.push.PushCategory;
import dev.aegis.notifications.domain.push.PushPreferences;
import dev.aegis.notifications.domain.push.PushPreferencesRepository;
import dev.aegis.notifications.domain.push.QuietHours;
import dev.aegis.shared.MemberId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One row per member who changed the push preferences; the first change inserts version 1, later ones are
 * version-checked updates. Categories are stored as a comma-separated list of stable names.
 */
@Component
public class JdbcPushPreferencesRepository implements PushPreferencesRepository {
    private final JdbcClient jdbc;

    JdbcPushPreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PushPreferences> findByMember(MemberId member) {
        return jdbc.sql("""
                SELECT member_id, categories, quiet_start, quiet_end, show_details, version, updated_at
                  FROM push_preferences WHERE member_id = ?""").param(member.value()).query(this::map).optional();
    }

    @Override
    public boolean save(PushPreferences p, long previousVersion) {
        String categories = p.categories().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        Time start = p.quietHours().map(q -> Time.valueOf(q.start())).orElse(null);
        Time end = p.quietHours().map(q -> Time.valueOf(q.end())).orElse(null);
        Timestamp updatedAt = Timestamp.from(p.updatedAt().orElseThrow());
        if (previousVersion == 0) {
            return jdbc.sql("""
                    INSERT INTO push_preferences (member_id, categories, quiet_start, quiet_end, show_details, version,
                                                  updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (member_id) DO NOTHING""")
                    .params(p.member().value(), categories, start, end, p.showDetails(), p.version(), updatedAt)
                    .update() == 1;
        }
        return jdbc.sql("""
                UPDATE push_preferences SET categories = ?, quiet_start = ?, quiet_end = ?, show_details = ?,
                       version = ?, updated_at = ?
                 WHERE member_id = ? AND version = ?""")
                .params(categories, start, end, p.showDetails(), p.version(), updatedAt, p.member().value(),
                        previousVersion)
                .update() == 1;
    }

    private PushPreferences map(ResultSet row, int index) throws SQLException {
        Set<PushCategory> categories = EnumSet.noneOf(PushCategory.class);
        Arrays.stream(row.getString("categories").split(",")).filter(name -> !name.isBlank())
                .forEach(name -> categories.add(PushCategory.valueOf(name)));
        Time start = row.getTime("quiet_start");
        Time end = row.getTime("quiet_end");
        return PushPreferences.rehydrate(new MemberId(row.getObject("member_id", UUID.class)), categories,
                start == null ? null : new QuietHours(start.toLocalTime(), end.toLocalTime()),
                row.getBoolean("show_details"), row.getLong("version"), row.getTimestamp("updated_at").toInstant());
    }
}
