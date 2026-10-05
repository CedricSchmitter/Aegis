package dev.aegis.documents.adapters.paperless;

import dev.aegis.documents.application.api.DocumentThumbnail;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveSearchResult;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.OriginalFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final RestClient searchClient;
    private final ArchiveSearchPager searchPager;

    public PaperlessClient(RestClient paperlessRestClient, ObjectMapper mapper) {
        this(paperlessRestClient, paperlessRestClient, mapper);
    }

    @Autowired
    public PaperlessClient(@Qualifier("paperlessRestClient") RestClient paperlessRestClient,
            @Qualifier("paperlessSearchRestClient") RestClient searchClient,
            ObjectMapper mapper) {
        this(paperlessRestClient, searchClient, mapper, new ArchiveSearchPager());
    }

    PaperlessClient(RestClient client, RestClient searchClient, ObjectMapper mapper, ArchiveSearchPager searchPager) {
        this.client = client;
        this.searchClient = searchClient;
        this.mapper = mapper;
        this.searchPager = searchPager;
    }

    @Override
    public SubmissionOutcome submit(Path file, String title, String originalFilename, String contentType) {
        byte[] body;
        String boundary = "----aegis" + UUID.randomUUID();
        try {
            body = multipart(boundary, title, originalFilename, contentType, Files.readAllBytes(file));
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

    /** Paperless keeps the OCR text of a document in its {@code content} field. */
    @Override
    public String fetchContent(int paperlessDocumentId) {
        try {
            JsonNode document = getJson("/api/documents/{id}/?fields=id,content", paperlessDocumentId);
            return document.path("content").asText("");
        } catch (ArchiveRejectedException rejected) {
            throw new ArchiveUnavailableException("Paperless did not return the text of document "
                    + paperlessDocumentId);
        }
    }

    /** {@code archived_file_name} is set when Paperless keeps an archive version (see {@link #fetchArchivePdf}). */
    @Override
    public ArchivedText fetchArchivedText(int paperlessDocumentId) {
        try {
            JsonNode document = getTextJson("/api/documents/{id}/?fields=id,content,archived_file_name",
                    paperlessDocumentId);
            return new ArchivedText(document.path("content").asText(""),
                    hasArchiveVersion(document.path("archived_file_name")));
        } catch (ArchiveRejectedException rejected) {
            throw new ArchiveUnavailableException("Paperless did not return the text of document "
                    + paperlessDocumentId);
        }
    }

    private static boolean hasArchiveVersion(JsonNode archivedFileName) {
        return archivedFileName.isTextual() && !archivedFileName.asText().isBlank();
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

    /**
     * Paperless names the archive version in {@code archived_file_name} and serves it from the download endpoint
     * without {@code original=true}; without an archive version that endpoint would serve the original instead.
     */
    @Override
    public Optional<byte[]> fetchArchivePdf(int paperlessDocumentId) {
        JsonNode document;
        try {
            document = getJson("/api/documents/{id}/?fields=id,archived_file_name", paperlessDocumentId);
        } catch (ArchiveRejectedException rejected) {
            throw new ArchiveUnavailableException("Paperless did not describe document " + paperlessDocumentId);
        }
        if (!hasArchiveVersion(document.path("archived_file_name"))) {
            return Optional.empty();
        }
        try {
            byte[] bytes = boundedDownload("/api/documents/{id}/download/", paperlessDocumentId);
            byte[] head = bytes == null ? new byte[0]
                    : Arrays.copyOf(bytes, Math.min(bytes.length, OriginalFormat.SIGNATURE_LENGTH));
            if (OriginalFormat.detect(head).filter(format -> format == OriginalFormat.PDF).isEmpty()) {
                throw new ArchiveUnavailableException("Paperless did not return a PDF archive version of document "
                        + paperlessDocumentId);
            }
            return Optional.of(bytes);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ArchiveUnavailableException("Paperless no longer lists document " + paperlessDocumentId);
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Paperless renders a WebP thumbnail of the first page when it consumes a document (PNG in older versions). The
     * type is taken from the bytes, never from the answer's header, so nothing but an image is ever served onward.
     */
    @Override
    public DocumentThumbnail fetchThumbnail(int paperlessDocumentId) {
        try {
            byte[] bytes = client.get().uri("/api/documents/{id}/thumb/", paperlessDocumentId).retrieve()
                    .body(byte[].class);
            byte[] image = bytes == null ? new byte[0] : bytes;
            return new DocumentThumbnail(thumbnailType(image).orElseThrow(() -> new ArchiveUnavailableException(
                    "Paperless did not return an image thumbnail of document " + paperlessDocumentId)), image);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ArchiveUnavailableException("Paperless no longer lists document " + paperlessDocumentId);
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    /** WebP, PNG or JPEG by their signatures; anything else is not a thumbnail. */
    static Optional<String> thumbnailType(byte[] bytes) {
        return OriginalFormat.detect(Arrays.copyOf(bytes, Math.min(bytes.length, OriginalFormat.SIGNATURE_LENGTH)))
                .filter(format -> format == OriginalFormat.WEBP || format == OriginalFormat.PNG
                        || format == OriginalFormat.JPEG)
                .map(OriginalFormat::contentType);
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

    /**
     * Paperless moves a deleted document into its own trash, where it keeps the file and its checksum. The document
     * is therefore deleted and then emptied from that trash by its id; the trash endpoint must never be called
     * without ids, because that empties the whole Paperless trash. The empty action reports success even when it
     * failed internally, so the result is checked: the document is gone when it is neither listed nor in the trash,
     * which the trash endpoint confirms by refusing the id with 400. Should a second empty still find it there, the
     * outcome is uncertain and the next attempt checks again.
     */
    @Override
    public void purge(int paperlessDocumentId) {
        if (exists(paperlessDocumentId)) {
            delete("/api/documents/{id}/", paperlessDocumentId);
        }
        emptyFromTrash(paperlessDocumentId);
        if (exists(paperlessDocumentId) || emptyFromTrash(paperlessDocumentId)) {
            throw new ArchiveUnavailableException("Paperless still holds document " + paperlessDocumentId
                    + " after its deletion");
        }
    }

    private void delete(String uri, int paperlessDocumentId) {
        try {
            client.delete().uri(uri, paperlessDocumentId).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                throw translate(e);
            }
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    /**
     * Empties one document from the Paperless trash. True when Paperless accepted it, false when it refused the id
     * because the document is not in its trash (400).
     */
    private boolean emptyFromTrash(int paperlessDocumentId) {
        try {
            client.post().uri("/api/trash/").contentType(MediaType.APPLICATION_JSON)
                    .body("{\"action\":\"empty\",\"documents\":[" + paperlessDocumentId + "]}").retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                return false;
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new ArchiveUnavailableException("Paperless unreachable: " + e.getMessage(), e);
        }
    }

    @Override
    public ArchiveSearchResult searchContent(String query) {
        return searchPager.search((number, remaining) -> {
            // A virtual task bounds the whole request (including response parsing) by the traversal deadline.
            // Cancellation stops further pages; the dedicated transport also bounds blocked socket work.
            var request = new FutureTask<ArchiveSearchPager.Page>(() -> {
                URI[] uri = new URI[1];
                String raw = searchClient.get().uri(builder -> {
                    var search = builder.path("/api/documents/").queryParam("text", "{q}")
                            .queryParam("fields", "id").queryParam("page_size", ArchiveSearchPager.PAGE_SIZE);
                    if (number > 1) search.queryParam("page", number);
                    uri[0] = search.build(query);
                    return uri[0];
                }).retrieve().body(String.class);
                return new ArchiveSearchPager.Page(mapper.readTree(raw == null ? "{}" : raw), uri[0]);
            });
            Thread.ofVirtual().start(request);
            try {
                return request.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ArchiveUnavailableException("Archive search interrupted", interrupted);
            } catch (ExecutionException failed) {
                throw new ArchiveUnavailableException("Archive search failed", failed);
            } finally {
                request.cancel(true);
            }
        });
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

    @Override
    public OriginalContent fetchTextOriginal(int id) {
        return new OriginalContent("document-"+id+".pdf", "application/pdf",
                boundedDownload("/api/documents/{id}/download/?original=true",id),OriginalContent.SOURCE_ARCHIVE);
    }

    private byte[] boundedDownload(String uri,int id) {
        try {
            return client.get().uri(uri,id).exchange((request,response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new ArchiveUnavailableException("Paperless source answered "+response.getStatusCode());
                }
                byte[] body=response.getBody().readNBytes(dev.aegis.documents.application.DocumentTextExtractor.MAX_BYTES+1);
                if (body.length>dev.aegis.documents.application.DocumentTextExtractor.MAX_BYTES) {
                    throw new dev.aegis.documents.application.DocumentTextExtractor.Refused("text.byteLimit");
                }
                return body;
            });
        } catch (ResourceAccessException unavailable) {
            throw new ArchiveUnavailableException("Paperless source unavailable",unavailable);
        }
    }

    private JsonNode getTextJson(String uri,Object... variables) {
        try {
            return client.get().uri(uri,variables).exchange((request,response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new ArchiveUnavailableException("Paperless text answered "+response.getStatusCode());
                }
                // JSON escaping can take six ASCII bytes per character. Refuse instead of truncating source text.
                int bound=dev.aegis.documents.application.DocumentTextExtractor.MAX_CHARACTERS*6+262144;
                byte[] body=response.getBody().readNBytes(bound+1);
                if (body.length>bound) { throw new dev.aegis.documents.application.DocumentTextExtractor.Refused("text.characterLimit"); }
                JsonNode value=mapper.readTree(body);
                if (value==null || !value.path("content").isTextual()) {
                    throw new ArchiveUnavailableException("Paperless did not supply original OCR text");
                }
                return value;
            });
        } catch (ResourceAccessException unavailable) {
            throw new ArchiveUnavailableException("Paperless text unavailable",unavailable);
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
     * request does not depend on the client's converter configuration and always carries a Content-Length. Paperless
     * recognizes the type from the content itself; the part still declares the verified media type.
     */
    static byte[] multipart(String boundary, String title, String originalFilename, String contentType,
            byte[] content) {
        String safeName = originalFilename.replace('"', '_').replace('\r', ' ').replace('\n', ' ');
        String safeType = OriginalFormat.ofContentType(contentType).map(OriginalFormat::contentType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        try {
            out.write(("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"title\"" + CRLF
                    + "Content-Type: text/plain; charset=UTF-8" + CRLF + CRLF + title + CRLF)
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + CRLF + "Content-Disposition: form-data; name=\"document\"; filename=\""
                    + safeName + "\"" + CRLF + "Content-Type: " + safeType + CRLF + CRLF)
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
