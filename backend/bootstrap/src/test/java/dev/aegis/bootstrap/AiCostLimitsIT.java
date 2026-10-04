package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The household's daily and session limit per workload through the REST API against PostgreSQL: shared by both
 * members, persisted with who changed them, protected by optimistic concurrency and validated as money amounts.
 */
class AiCostLimitsIT extends PostgresIT {
    private static final String CHAT = "/api/ai/workloads/CHAT/limits";

    @LocalServerPort
    int port;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void defaults() {
        jdbc.sql("DELETE FROM ai_cost_limits").update();
    }

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static JsonNode workload(JsonNode overview, String name) {
        return StreamSupport.stream(overview.path("workloads").spliterator(), false)
                .filter(workload -> workload.path("workload").asText().equals(name)).findFirst().orElseThrow();
    }

    private static String body(String daily, String run, long version) {
        return "{\"dailyCostLimit\":" + daily + ",\"maxRunCost\":" + run + ",\"expectedVersion\":" + version + "}";
    }

    @Test
    void limitsAreSharedPersistedAndVersioned() {
        ApiClient anna = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        JsonNode before = workload(anna.json(anna.get("/api/ai/overview")), "CHAT");
        assertThat(before.path("ownLimits").asBoolean()).isFalse();
        assertThat(before.path("limitsSelectable").asBoolean()).isTrue();
        assertThat(before.path("limitsVersion").asLong()).isZero();

        ApiClient.Response set = anna.putJson(CHAT, body("4.50", "0.75", 0));

        assertThat(set.status()).as(set.body()).isEqualTo(200);
        JsonNode chat = workload(anna.json(set), "CHAT");
        assertThat(chat.path("ownLimits").asBoolean()).isTrue();
        assertThat(chat.path("dailyCostLimit").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(chat.path("maxCost").decimalValue()).isEqualByComparingTo("0.75");
        assertThat(chat.path("spentToday").isNumber()).as("other tests may have spent on chat today").isTrue();
        assertThat(chat.path("limitsVersion").asLong()).isEqualTo(1);
        assertThat(chat.path("limitsChangedBy").asText())
                .isEqualTo(members.findCredentials(ANNA).orElseThrow().id().value().toString());
        assertThat(workload(anna.json(set), "DOCUMENT_CLASSIFICATION").path("ownLimits").asBoolean()).isFalse();

        ApiClient.Response stale = ben.putJson(CHAT, body("1.00", "0.10", 0));
        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        JsonNode seenByBen = workload(ben.json(ben.get("/api/ai/overview")), "CHAT");
        assertThat(seenByBen.path("dailyCostLimit").decimalValue()).isEqualByComparingTo("4.50");
        Map<String, Object> stored = jdbc.sql("SELECT daily_limit, run_limit, currency, version FROM ai_cost_limits "
                + "WHERE workload = 'CHAT'").query().singleRow();
        assertThat((BigDecimal) stored.get("daily_limit")).isEqualByComparingTo("4.50");
        assertThat((BigDecimal) stored.get("run_limit")).isEqualByComparingTo("0.75");
        assertThat(stored.get("currency")).isEqualTo("USD");
        assertThat(stored.get("version")).isEqualTo(1L);

        ApiClient.Response changed = ben.putJson(CHAT, body("1.00", "0.10", 1));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(workload(ben.json(changed), "CHAT").path("limitsVersion").asLong()).isEqualTo(2);
    }

    @Test
    void invalidLimitsAreRejectedWithStableCodes() {
        ApiClient anna = loggedIn(ANNA);

        for (Map.Entry<String, String> invalid : List.of(
                Map.entry(CHAT, body("0", "0.10", 0)),
                Map.entry(CHAT, body("1.005", "0.10", 0)),
                Map.entry(CHAT, body("1000.01", "0.10", 0)),
                Map.entry(CHAT, body("0.50", "0.60", 0)),
                Map.entry(CHAT, body("null", "0.10", 0)),
                Map.entry("/api/ai/workloads/CONNECTION_CHECK/limits", body("1", "0.1", 0)),
                Map.entry("/api/ai/workloads/SHOPPING/limits", body("1", "0.1", 0)))) {
            ApiClient.Response response = anna.putJson(invalid.getKey(), invalid.getValue());
            assertThat(response.status()).as(invalid.getValue() + " " + response.body()).isEqualTo(400);
            assertThat(anna.json(response).path("code").asText()).isIn(List.of("ai.limits.notPositive",
                    "ai.limits.tooPrecise", "ai.limits.tooHigh", "ai.limits.runAboveDaily",
                    "ai.limits.notSelectable", "ai.workload.unknown"));
        }
        ApiClient.Response letters = anna.putJson(CHAT, body("\"zehn\"", "0.10", 0));
        assertThat(letters.status()).as(letters.body()).isEqualTo(400);
        assertThat(new ApiClient(port).putJson(CHAT, body("1", "0.1", 0)).status()).isIn(401, 403);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_cost_limits").query(Long.class).single()).isZero();
    }
}
