package dev.aegis.documents.adapters.paperless;

import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.PaperlessArchive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Paperless-ngx REST adapter (API version 10). Transport and 5xx errors become {@link ArchiveUnavailableException};
 * 4xx answers become {@link ArchiveRejectedException}. No document content is logged.
 */
@Component
public class PaperlessClient implements PaperlessArchive {
    static final String ACCEPT_V10 = "application/json; version=10";
    private static final String CRLF = "\r\n";
    private static final Logger log = LoggerFactory.getLogger(PaperlessClient.class);

    private final RestClient client;
    private final ObjectMapper mapper;

    public PaperlessClient(RestClient paperlessRestClient, ObjectMapper mapper) {
        this.client = paperlessRestClient;
        this.mapper = mapper;
    }

    @Override
    public SubmissionOutcome submit(Path file, String title, String originalFilename) {
        byte[] body;
        String boundary = "----aegis" + UUID.randomUUID();
        try {
            body = multipart(boundary, title, originalFilename, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read staging file " + file, e);
        }
        try {
            String raw = client.post().uri("/api/documents/post_document/")
                    .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                    .body(body).retrieve().body(String.class);
            String taskId = raw == null ? "" : raw.trim().replace("\"", "");
            if (taskId.isEmpty()) {
                return new Rejected("paperless.noTaskId", "Paperless returned no consumption task id");
            }
            return new Accepted(taskId);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new ArchiveUnavailableException("Paperless answered " + e.getStatusCode(), e);
            }
            return new Rejected("paperless.rejected", "Paperless rejected the upload with " + e.getStatusCode()
                    + excerpt(e));
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskOutcome checkTask(String taskId) {
        JsonNode page = getJson("/api/tasks/?task_id={id}", taskId);
        JsonNode results = page.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return new Pending();
        }
        JsonNode task = results.get(0);
        String status = task.path("status").asText("").toLowerCase(Locale.ROOT);
        JsonNode result = task.path("result_data");
        return switch (status) {
            case "success" -> {
                JsonNode id = result.path("document_id");
                yield id.isNumber() ? new Completed(id.asInt()) : new Failed("paperless.noDocumentId",
                        "consumption succeeded without a document id");
            }
            case "failure" -> {
                JsonNode duplicate = result.path("duplicate_of");
                if (duplicate.isNumber()) {
                    yield new Duplicate(duplicate.asInt());
                }
                yield new Failed("paperless.consumptionFailed", failureMessage(result));
            }
            case "revoked" -> new Failed("paperless.revoked", "consumption task was revoked");
            default -> new Pending();
        };
    }

    @Override
    public OriginalContent fetchOriginal(int paperlessDocumentId) {
        try {
            ResponseEntity<byte[]> response = client.get()
                    .uri("/api/documents/{id}/download/?original=true", paperlessDocumentId).retrieve()
                    .toEntity(byte[].class);
            byte[] bytes = response.getBody() == null ? new byte[0] : response.getBody();
            String contentType = response.getHeaders().getContentType() == null ? MediaType.APPLICATION_PDF_VALUE
                    : response.getHeaders().getContentType().toString();
            return new OriginalContent("document-" + paperlessDocumentId + ".pdf", contentType, bytes,
                    OriginalContent.SOURCE_ARCHIVE);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ArchiveUnavailableException("Paperless no longer lists document " + paperlessDocumentId);
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(int paperlessDocumentId) {
        try {
            client.get().uri("/api/documents/{id}/?fields=id", paperlessDocumentId).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Integer> searchContent(String query) {
        JsonNode page = getJson("/api/documents/?text={q}&fields=id&page_size=200", query);
        List<Integer> ids = new ArrayList<>();
        for (JsonNode node : page.path("results")) {
            if (node.path("id").isNumber()) {
                ids.add(node.path("id").asInt());
            }
        }
        return ids;
    }

    @Override
    public ArchiveProbe probe() {
        try {
            // An authenticated list endpoint; the API root redirects to the browsable schema UI.
            ResponseEntity<Void> response = client.get().uri("/api/documents/?page_size=1&fields=id").retrieve()
                    .toBodilessEntity();
            String version = response.getHeaders().getFirst("X-Version");
            return new ArchiveProbe(true, version, null);
        } catch (RestClientResponseException e) {
            return new ArchiveProbe(false, null, "Paperless answered " + e.getStatusCode());
        } catch (ResourceAccessException e) {
            return new ArchiveProbe(false, null, "Paperless unreachable: " + e.getMessage());
        }
    }

    private JsonNode getJson(String uri, Object... variables) {
        try {
            String body = client.get().uri(uri, variables).retrieve().body(String.class);
            return mapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    private static RuntimeException translate(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        if (status.is5xxServerError() || status.value() == 429) {
            return new ArchiveUnavailableException("Paperless answered " + status, e);
        }
        log.warn("Paperless rejected a request with {}", status);
        return new ArchiveRejectedException("paperless.rejected", "Paperless answered " + status);
    }

    /**
     * Builds the multipart body explicitly (a text part {@code title} and a file part {@code document}) so the
     * request does not depend on the client's converter configuration and always carries a Content-Length.
     */
    static byte[] multipart(String boundary, String title, String originalFilename, byte[] content) {
        String safeName = originalFilename.replace('"', '_').replace('\r', ' ').replace('\n', ' ');
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        try {
            out.write(("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"title\"" + CRLF
                    + "Content-Type: text/plain; charset=UTF-8" + CRLF + CRLF + title + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"document\"; filename=\""
                    + safeName + "\"" + CRLF + "Content-Type: application/pdf" + CRLF + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write((CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Short, content-free excerpt of an error body for diagnostics (validation messages, never documents). */
    private static String excerpt(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return ": " + (flat.length() > 200 ? flat.substring(0, 200) + "…" : flat);
    }

    private static String failureMessage(JsonNode result) {
        for (String key : List.of("message", "error", "result", "detail")) {
            JsonNode node = result.path(key);
            if (node.isTextual() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return result.isMissingNode() || result.isNull() ? "consumption failed" : result.toString();
    }
}
