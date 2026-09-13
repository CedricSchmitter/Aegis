package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class HouseholdOnboardingIT extends PostgresIT {
    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int port;

    @Test
    void explicitOnboardingPreservesIdsCredentialsAndExistingSessions() throws Exception {
        ApiClient session = new ApiClient(port);
        assertThat(session.login(ANNA, PASSWORD)).isTrue();
        var before = jdbc.queryForList("SELECT id, password_hash, active, created_at FROM members ORDER BY id");
        String sql = Files.readString(Path.of("../../ops/deploy/onboard-household.sql"));
        try {
            jdbc.execute(sql);
            jdbc.execute(sql);
            assertThat(jdbc.queryForList("SELECT id, password_hash, active, created_at FROM members ORDER BY id"))
                    .isEqualTo(before);
            assertThat(session.json(session.get("/api/auth/session")).path("member").path("username").asText())
                    .isEqualTo("lisa");
            assertThat(new ApiClient(port).login("lisa", PASSWORD)).isTrue();
            assertThat(new ApiClient(port).login("cedric", PASSWORD)).isTrue();
            assertThat(new ApiClient(port).login(ANNA, PASSWORD)).isFalse();
            assertThat(new ApiClient(port).login(BEN, PASSWORD)).isFalse();
        } finally {
            jdbc.update("UPDATE members SET username='anna', display_name='Anna' WHERE username='lisa'");
            jdbc.update("UPDATE members SET username='ben', display_name='Ben' WHERE username='cedric'");
        }
    }
}
