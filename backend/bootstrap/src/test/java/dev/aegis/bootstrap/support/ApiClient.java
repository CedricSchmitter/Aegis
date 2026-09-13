package dev.aegis.bootstrap.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal browser-like HTTP client: cookie jar, CSRF header echo and JSON helpers. One instance per session. */
public class ApiClient {
    private final String baseUrl;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public record Response(int status, String body, Map<String, List<String>> headers, byte[] bytes) {
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public Response postJson(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8)));
    }

    public Response putJson(String path, String json) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    /** Multipart upload like a browser form: field "file" plus repeated "subjects" values. */
    public Response upload(String path, String filename, byte[] content, List<String> subjects) {
        String boundary = "----aegis" + System.nanoTime();
        var body = new java.io.ByteArrayOutputStream();
        try {
            for (String subject : subjects) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"subjects\"\r\n\r\n"
                        + subject + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    public Response postWithoutCsrf(String path, String json) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return execute(request);
    }

    public JsonNode json(Response response) {
        return mapper.readTree(response.body());
    }

    public boolean login(String username, String password) {
        // Any request establishes the CSRF cookie; the session endpoint is the natural first call of the SPA.
        get("/api/auth/session");
        Response response = postJson("/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        return response.status() == 200;
    }

    public String csrfToken() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private Response send(HttpRequest.Builder builder) {
        String token = csrfToken();
        if (token != null) {
            builder.header("X-XSRF-TOKEN", token);
        }
        return execute(builder.build());
    }

    private Response execute(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8),
                    response.headers().map(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
