package dev.aegis.bootstrap;

import static dev.aegis.bootstrap.support.ControlledModelGateway.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.ControlledModelGateway;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import java.time.Duration;
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
 * The household's model family per workload through the REST API against PostgreSQL: shared by both members,
 * persisted with who changed it, protected by optimistic concurrency and used by the next runs.
 */
class AiModelChoiceIT extends PostgresIT {
    private static final String REVIEW = "/api/ai/workloads/DOCUMENT_REVIEW/model";

    @LocalServerPort
    int port;
    @Autowired
    ControlledModelGateway model;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void defaults() {
        model.reset();
        jdbc.sql("DELETE FROM ai_model_choices").update();
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

    @Test
    void aChoiceIsSharedPersistedVersionedAndUsedByTheNextRuns() {
        ApiClient anna = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        JsonNode before = anna.json(anna.get("/api/ai/overview"));
        assertThat(workload(before, "DOCUMENT_REVIEW").path("family").asText()).isEqualTo("sonnet");
        assertThat(workload(before, "DOCUMENT_REVIEW").path("choiceVersion").asLong()).isZero();
        assertThat(before.path("models")).extracting(option -> option.path("family").asText())
                .containsExactly("opus", "sonnet", "haiku");

        ApiClient.Response chosen = anna.putJson(REVIEW, "{\"family\":\"opus\",\"expectedVersion\":0}");

        assertThat(chosen.status()).as(chosen.body()).isEqualTo(200);
        JsonNode review = workload(anna.json(chosen), "DOCUMENT_REVIEW");
        assertThat(review.path("family").asText()).isEqualTo("opus");
        assertThat(review.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(review.path("choiceVersion").asLong()).isEqualTo(1);
        assertThat(review.path("choiceChangedBy").asText())
                .isEqualTo(members.findCredentials(ANNA).orElseThrow().id().value().toString());
        assertThat(workload(anna.json(chosen), "DOCUMENT_CLASSIFICATION").path("family").asText())
                .isEqualTo("sonnet");

        ApiClient.Response stale = ben.putJson(REVIEW, "{\"family\":\"haiku\",\"expectedVersion\":0}");
        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        assertThat(workload(ben.json(ben.get("/api/ai/overview")), "DOCUMENT_REVIEW").path("family").asText())
                .isEqualTo("opus");
        assertThat(jdbc.sql("SELECT family FROM ai_model_choices WHERE workload = 'DOCUMENT_REVIEW'")
                .query(String.class).single()).isEqualTo("OPUS");

        model.answer(json(Map.of("status", "ok"))).answer(json(Map.of("status", "ok")));
        ApiClient.Response checks = ben.postJson("/api/ai/connection-checks", null);
        assertThat(checks.status()).as(checks.body()).isEqualTo(202);
        assertThat(ben.json(checks)).extracting(check -> check.path("model").asText())
                .containsExactlyInAnyOrder(ControlledModelGateway.MODEL, "claude-opus-5-5");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(model.requests)
                .extracting(request -> request.model())
                .containsExactlyInAnyOrder(ControlledModelGateway.MODEL, "claude-opus-5-5"));
    }

    @Test
    void invalidChoicesAreRejectedWithStableCodes() {
        ApiClient anna = loggedIn(ANNA);

        for (Map.Entry<String, String> invalid : Map.of(
                REVIEW, "{\"family\":\"claude-opus-5-5\",\"expectedVersion\":0}",
                "/api/ai/workloads/CONNECTION_CHECK/model", "{\"family\":\"opus\",\"expectedVersion\":0}",
                "/api/ai/workloads/SHOPPING/model", "{\"family\":\"opus\",\"expectedVersion\":0}").entrySet()) {
            ApiClient.Response response = anna.putJson(invalid.getKey(), invalid.getValue());
            assertThat(response.status()).as(response.body()).isEqualTo(400);
            assertThat(anna.json(response).path("code").asText())
                    .isIn(List.of("ai.model.unknownFamily", "ai.model.notSelectable", "ai.workload.unknown"));
        }
        assertThat(new ApiClient(port).putJson(REVIEW, "{\"family\":\"opus\",\"expectedVersion\":0}").status())
                .isIn(401, 403);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_model_choices").query(Long.class).single()).isZero();
    }
}
