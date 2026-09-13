package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the committed OpenAPI contract ({@code api/openapi.json}) in sync with the running backend. The frontend
 * client is generated from that file, so drift fails CI. Run with {@code -Daegis.openapi.export=true} to refresh.
 */
class OpenApiSnapshotIT extends PostgresIT {
    static final Path SNAPSHOT = Path.of("..", "..", "api", "openapi.json");

    @LocalServerPort
    int port;

    @Test
    void committedContractMatchesTheRunningBackend() throws IOException {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(ANNA, PASSWORD)).isTrue();
        ApiClient.Response response = client.get("/api/openapi.json");
        assertThat(response.status()).isEqualTo(200);
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        JsonNode live = mapper.readTree(response.body());
        ((tools.jackson.databind.node.ObjectNode) live).remove("servers");
        String normalized = mapper.writeValueAsString(mapper.treeToValue(live, java.util.Map.class)) + "\n";

        if (Boolean.getBoolean("aegis.openapi.export")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, normalized, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.isRegularFile(SNAPSHOT)).as("api/openapi.json committed").isTrue();
        String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(normalized).as("api/openapi.json is stale; regenerate with -Daegis.openapi.export=true")
                .isEqualTo(committed);
    }
}
