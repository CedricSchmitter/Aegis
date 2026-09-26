package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberAppearance;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Palette and effects are stored per member in PostgreSQL (issue #27, ADR-0019): the session carries them, a change
 * affects only the acting member, survives a new session, rides on the member's optimistic-concurrency version and is
 * validated at the API boundary. Members stored before the migration read as Streetkid with effects on.
 */
class MemberAppearanceIT extends PostgresIT {
    private static final String DEFAULT = "{\"palette\":\"STREETKID\",\"effects\":true}";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MemberDirectory directory;

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).as("login " + username).isTrue();
        return client;
    }

    @AfterEach
    void restoreDefaults() {
        jdbc.update("UPDATE members SET palette = 'STREETKID', effects_enabled = TRUE WHERE username IN (?, ?)", ANNA,
                BEN);
    }

    @Test
    void aMembersChoiceIsStoredForThemOnlyAndComesBackWithTheNextSession() {
        ApiClient anna = loggedIn(ANNA);
        JsonNode before = anna.json(anna.get("/api/auth/session")).path("appearance");
        assertThat(before.path("palette").asText()).isEqualTo("STREETKID");
        assertThat(before.path("effects").asBoolean()).isTrue();
        long versionBefore = version(ANNA);

        ApiClient.Response saved = anna.putJson("/api/members/me/appearance",
                "{\"palette\":\"NOMAD\",\"effects\":false}");

        assertThat(saved.status()).isEqualTo(200);
        assertThat(anna.json(saved).path("palette").asText()).isEqualTo("NOMAD");
        assertThat(anna.json(saved).path("effects").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT palette FROM members WHERE username = ?", String.class, ANNA))
                .isEqualTo("NOMAD");
        assertThat(version(ANNA)).as("the member's optimistic-concurrency version").isEqualTo(versionBefore + 1);

        ApiClient again = loggedIn(ANNA);
        JsonNode login = again.json(again.get("/api/auth/session"));
        assertThat(login.path("appearance").path("palette").asText()).isEqualTo("NOMAD");
        assertThat(login.path("appearance").path("effects").asBoolean()).isFalse();
        assertThat(login.path("member").path("locale").asText()).as("the locale is untouched").isEqualTo("DE");

        ApiClient ben = loggedIn(BEN);
        assertThat(ben.json(ben.get("/api/auth/session")).path("appearance").path("palette").asText())
                .isEqualTo("STREETKID");
        assertThat(ben.json(ben.get("/api/members")).findValues("appearance"))
                .as("the directory does not publish another member's look").isEmpty();

        assertThat(anna.json(anna.putJson("/api/members/me/appearance", DEFAULT)).path("palette").asText())
                .isEqualTo("STREETKID");
    }

    @Test
    void invalidValuesAreRejectedAndChangeNothing() {
        ApiClient anna = loggedIn(ANNA);
        anna.putJson("/api/members/me/appearance", "{\"palette\":\"CORPO\",\"effects\":true}");

        assertThat(anna.putJson("/api/members/me/appearance", "{\"palette\":\"NEON\",\"effects\":true}").status())
                .isEqualTo(400);
        assertThat(anna.putJson("/api/members/me/appearance", "{\"palette\":\"NOMAD\"}").status()).isEqualTo(400);
        assertThat(anna.putJson("/api/members/me/appearance", "{\"effects\":false}").status()).isEqualTo(400);
        assertThat(new ApiClient(port).putJson("/api/members/me/appearance", DEFAULT).status())
                .as("anonymous").isIn(401, 403);

        JsonNode appearance = anna.json(anna.get("/api/auth/session")).path("appearance");
        assertThat(appearance.path("palette").asText()).isEqualTo("CORPO");
        assertThat(appearance.path("effects").asBoolean()).isTrue();
    }

    @Test
    void membersStoredBeforeTheMigrationReadAsStreetkidWithEffectsOn() {
        UUID id = UUID.randomUUID();
        // A row written the way V1_1/V1_2 did, without the appearance columns: the migration's defaults fill them.
        jdbc.update("INSERT INTO members (id, username, display_name, password_hash, locale, active, kind, created_at,"
                + " version) VALUES (?, ?, ?, ?, 'DE', FALSE, 'PERSON', ?, 0)", id, "legacy-" + id.toString()
                .substring(0, 8), "Legacy", "{noop}unused", java.sql.Timestamp.from(Instant.EPOCH));
        try {
            assertThat(directory.findMember(new MemberId(id)).orElseThrow().appearance())
                    .isEqualTo(MemberAppearance.DEFAULT);
        } finally {
            jdbc.update("DELETE FROM members WHERE id = ?", id);
        }
    }

    private long version(String username) {
        return jdbc.queryForObject("SELECT version FROM members WHERE username = ?", Long.class, username);
    }
}
