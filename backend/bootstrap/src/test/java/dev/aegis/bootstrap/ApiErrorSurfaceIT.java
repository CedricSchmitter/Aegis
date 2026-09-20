package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Requests the API does not serve, through the real filter chain: the status a client gets, and that an unknown
 * path tells an unauthenticated caller no more than a known one. The problem-detail bodies and the SPA fallback
 * are covered without a database by {@code ApiErrorResponseTest}.
 */
class ApiErrorSurfaceIT extends PostgresIT {
    @LocalServerPort
    int port;

    @Test
    void anUnknownApiPathIsNotFoundForAMember() {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(ANNA, PASSWORD)).isTrue();

        ApiClient.Response response = client.get("/api/ai");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(client.json(response).path("code").asString()).isEqualTo("request.pathNotFound");
    }

    @Test
    void anUnsupportedMethodOrMediaTypeOnAKnownPathIsNotAnUnexpectedFailure() {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(ANNA, PASSWORD)).isTrue();

        ApiClient.Response wrongMethod = client.request("DELETE", "/api/tasks", null, null);
        assertThat(wrongMethod.status()).isEqualTo(405);
        assertThat(wrongMethod.header("Allow")).contains("GET");
        assertThat(client.json(wrongMethod).path("code").asString()).isEqualTo("request.methodNotAllowed");

        ApiClient.Response wrongMediaType = client.request("POST", "/api/tasks", "text/plain", "title");
        assertThat(wrongMediaType.status()).isEqualTo(415);
        assertThat(client.json(wrongMediaType).path("code").asString()).isEqualTo("request.unsupportedMediaType");
    }

    @Test
    void anUnknownApiPathRevealsNothingMoreThanAKnownOneWithoutASession() {
        ApiClient anonymous = new ApiClient(port);

        ApiClient.Response unknown = anonymous.get("/api/ai");
        ApiClient.Response known = anonymous.get("/api/tasks");

        assertThat(unknown.status()).isEqualTo(401);
        assertThat(unknown.body()).isEqualTo(known.body());
        assertThat(anonymous.json(unknown).path("code").asString()).isEqualTo("auth.required");
    }
}
