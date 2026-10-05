package dev.aegis.documents.adapters.paperless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.aegis.documents.application.ports.ArchiveRejectedException;
import dev.aegis.documents.application.ports.ArchiveUnavailableException;
import dev.aegis.documents.application.ports.PaperlessArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the HTTP mapping of the Paperless adapter against controlled responses. */
class PaperlessClientTest {
    private MockRestServiceServer server;
    private PaperlessClient client;
    private RestClient transport;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://paperless.test")
                .defaultHeader("Accept", PaperlessClient.ACCEPT_V10)
                .defaultHeader("Authorization", "Basic synthetic-test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        transport = builder.build();
        client = new PaperlessClient(transport, JsonMapper.builder().build());
    }

    @Test
    void submitReturnsTaskIdFromQuotedResponse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.pdf");
        Files.write(file, "%PDF-x".getBytes());
        server.expect(requestTo("http://paperless.test/api/documents/post_document/")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", PaperlessClient.ACCEPT_V10))
                .andExpect(request -> {
                    String contentType = request.getHeaders().getContentType().toString();
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString();
                    assertThat(contentType).startsWith("multipart/form-data").contains("boundary=");
                    assertThat(body).contains("name=\"document\"; filename=\"x.pdf\"")
                            .contains("Content-Type: application/pdf").contains("%PDF-x")
                            .contains("name=\"title\"").contains("Title");
                })
                .andRespond(withSuccess("\"7d3d1b8a-1111-2222-3333-444455556666\"", MediaType.APPLICATION_JSON));

        var outcome = client.submit(file, "Title", "x.pdf", "application/pdf");

        assertThat(outcome).isEqualTo(new PaperlessArchive.Accepted("7d3d1b8a-1111-2222-3333-444455556666"));
    }

    @Test
    void aPhotoIsSubmittedWithItsVerifiedImageType(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("staged.jpg");
        Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        server.expect(requestTo("http://paperless.test/api/documents/post_document/")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString();
                    assertThat(body).contains("name=\"document\"; filename=\"IMG_1.JPG\"")
                            .contains("Content-Type: image/jpeg").doesNotContain("application/pdf");
                })
                .andRespond(withSuccess("\"task-photo\"", MediaType.APPLICATION_JSON));

        assertThat(client.submit(file, "Brief", "IMG_1.JPG", "image/jpeg"))
                .isEqualTo(new PaperlessArchive.Accepted("task-photo"));
        assertThat(new String(PaperlessClient.multipart("b", "t", "x", "text/html; x", new byte[0]),
                java.nio.charset.StandardCharsets.UTF_8)).as("an unverified type is never passed on")
                .contains("Content-Type: application/octet-stream");
    }

    @Test
    void theArchivePdfIsFetchedOnlyWhenPaperlessKeepsAnArchiveVersion() {
        byte[] archivePdf = "%PDF-1.7 archive".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.expect(requestTo("http://paperless.test/api/documents/5/?fields=id,archived_file_name"))
                .andRespond(withSuccess("{\"id\":5,\"archived_file_name\":\"2026-09-22 Brief.pdf\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/5/download/"))
                .andRespond(withSuccess(archivePdf, MediaType.APPLICATION_PDF));
        server.expect(requestTo("http://paperless.test/api/documents/6/?fields=id,archived_file_name"))
                .andRespond(withSuccess("{\"id\":6,\"archived_file_name\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/7/?fields=id,archived_file_name"))
                .andRespond(withSuccess("{\"id\":7,\"archived_file_name\":\"x.pdf\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/7/download/"))
                .andRespond(withSuccess(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, MediaType.IMAGE_JPEG));
        server.expect(requestTo("http://paperless.test/api/documents/8/?fields=id,archived_file_name"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetchArchivePdf(5)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(archivePdf));
        assertThat(client.fetchArchivePdf(6)).isEmpty();
        assertThatThrownBy(() -> client.fetchArchivePdf(7)).as("the original instead of an archive PDF")
                .isInstanceOf(ArchiveUnavailableException.class);
        assertThatThrownBy(() -> client.fetchArchivePdf(8)).isInstanceOf(ArchiveUnavailableException.class);
    }

    @Test
    void theThumbnailIsServedOnlyWhenItsBytesAreAnImage() {
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 image".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        server.expect(requestTo("http://paperless.test/api/documents/5/thumb/"))
                .andRespond(withSuccess(webp, MediaType.parseMediaType("image/webp")));
        server.expect(requestTo("http://paperless.test/api/documents/6/thumb/"))
                .andRespond(withSuccess("<html>login</html>", MediaType.parseMediaType("image/webp")));
        server.expect(requestTo("http://paperless.test/api/documents/7/thumb/"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/documents/8/thumb/")).andRespond(withServerError());

        var thumbnail = client.fetchThumbnail(5);
        assertThat(thumbnail.contentType()).isEqualTo("image/webp");
        assertThat(thumbnail.bytes()).isEqualTo(webp);
        assertThatThrownBy(() -> client.fetchThumbnail(6)).as("the header alone does not make an image")
                .isInstanceOf(ArchiveUnavailableException.class);
        assertThatThrownBy(() -> client.fetchThumbnail(7)).isInstanceOf(ArchiveUnavailableException.class);
        assertThatThrownBy(() -> client.fetchThumbnail(8)).isInstanceOf(ArchiveUnavailableException.class);
        assertThat(PaperlessClient.thumbnailType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
                .hasValue("image/jpeg");
        assertThat(PaperlessClient.thumbnailType("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isEmpty();
    }

    @Test
    void theTextIsReadTogetherWithWhetherPaperlessKeepsAnArchiveVersion() {
        String fields = "/?fields=id,content,archived_file_name";
        server.expect(requestTo("http://paperless.test/api/documents/5" + fields))
                .andRespond(withSuccess("{\"id\":5,\"content\":\"Rechnung\",\"archived_file_name\":\"Scan.pdf\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/6" + fields))
                .andRespond(withSuccess("{\"id\":6,\"content\":\"Vertrag\",\"archived_file_name\":null}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/7" + fields))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/8" + fields))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.fetchArchivedText(5)).isEqualTo(new PaperlessArchive.ArchivedText("Rechnung", true));
        assertThat(client.fetchArchivedText(6)).isEqualTo(new PaperlessArchive.ArchivedText("Vertrag", false));
        assertThat(client.fetchArchivedText(7)).isEqualTo(new PaperlessArchive.ArchivedText("", false));
        assertThatThrownBy(() -> client.fetchArchivedText(8)).isInstanceOf(ArchiveUnavailableException.class);
    }

    @Test
    void checkTaskMapsPendingSuccessDuplicateAndFailure() {
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=t1")).andRespond(withSuccess(
                "{\"count\":1,\"results\":[{\"task_id\":\"t1\",\"status\":\"started\",\"result_data\":null}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=t2")).andRespond(withSuccess(
                "{\"count\":1,\"results\":[{\"task_id\":\"t2\",\"status\":\"success\","
                        + "\"result_data\":{\"document_id\":42}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=t3")).andRespond(withSuccess(
                "{\"count\":1,\"results\":[{\"task_id\":\"t3\",\"status\":\"failure\","
                        + "\"result_data\":{\"duplicate_of\":7,\"message\":\"duplicate\"}}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=t4")).andRespond(withSuccess(
                "{\"count\":1,\"results\":[{\"task_id\":\"t4\",\"status\":\"failure\","
                        + "\"result_data\":{\"message\":\"Unsupported file\"}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=t5"))
                .andRespond(withSuccess("{\"count\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.checkTask("t1")).isInstanceOf(PaperlessArchive.Pending.class);
        assertThat(client.checkTask("t2")).isEqualTo(new PaperlessArchive.Completed(42));
        assertThat(client.checkTask("t3")).isEqualTo(new PaperlessArchive.Duplicate(7));
        assertThat(client.checkTask("t4")).isEqualTo(new PaperlessArchive.Failed("paperless.consumptionFailed",
                "Unsupported file"));
        assertThat(client.checkTask("t5")).isInstanceOf(PaperlessArchive.Pending.class);
    }

    @Test
    void transportAndServerErrorsAreRetryableWhileClientErrorsAreNot() {
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=down"))
                .andRespond(withException(new IOException("connection refused")));
        server.expect(requestTo("http://paperless.test/api/tasks/?task_id=err")).andRespond(withServerError());
        server.expect(requestTo("http://paperless.test/api/documents/9/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/documents/9/download/?original=true"))
                .andRespond(withSuccess(new byte[] {37, 80, 68, 70}, MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> client.checkTask("down")).isInstanceOf(ArchiveUnavailableException.class);
        assertThatThrownBy(() -> client.checkTask("err")).isInstanceOf(ArchiveUnavailableException.class);
        assertThat(client.exists(9)).isFalse();
        assertThat(client.fetchOriginal(9).bytes()).containsExactly(37, 80, 68, 70);
    }

    @Test
    void aPurgeDeletesTheDocumentAndEmptiesExactlyItFromThePaperlessTrash() {
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id")).andRespond(withSuccess());
        server.expect(requestTo("http://paperless.test/api/documents/42/")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://paperless.test/api/trash/")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string("{\"action\":\"empty\",\"documents\":[42]}"))
                .andRespond(withSuccess("{\"result\":\"OK\",\"doc_ids\":[42]}", MediaType.APPLICATION_JSON));
        // The check afterwards: no longer listed, and the trash refuses the id because it no longer holds it.
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/trash/"))
                .andRespond(withBadRequest().body("{\"documents\":[\"Some documents in the list have not yet been "
                        + "deleted.\"]}").contentType(MediaType.APPLICATION_JSON));

        client.purge(42);

        server.verify();
    }

    @Test
    void aRepeatedPurgeOfADocumentPaperlessNoLongerHoldsSucceedsWithoutDeleting() {
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/trash/")).andRespond(withBadRequest());
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/trash/")).andRespond(withBadRequest());

        client.purge(42);

        server.verify();
    }

    @Test
    void aPurgeWhoseTrashEmptyingSilentlyFailedIsUncertainAndRetried() {
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        // Paperless answers OK even when emptying failed internally; the second call still finds it in the trash.
        server.expect(requestTo("http://paperless.test/api/trash/")).andRespond(withSuccess());
        server.expect(requestTo("http://paperless.test/api/documents/42/?fields=id"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://paperless.test/api/trash/")).andRespond(withSuccess());

        assertThatThrownBy(() -> client.purge(42)).isInstanceOf(ArchiveUnavailableException.class);
    }

    @Test
    void aRefusedOrUnreachablePurgeIsReportedAsSuch() {
        server.expect(requestTo("http://paperless.test/api/documents/7/?fields=id")).andRespond(withSuccess());
        server.expect(requestTo("http://paperless.test/api/documents/7/")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("http://paperless.test/api/documents/8/?fields=id")).andRespond(withSuccess());
        server.expect(requestTo("http://paperless.test/api/documents/8/")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client.purge(7)).isInstanceOf(ArchiveRejectedException.class);
        assertThatThrownBy(() -> client.purge(8)).isInstanceOf(ArchiveUnavailableException.class);
    }

    @Test
    void contentOnlyMatchesBeyondTheFirstTwoHundredAreCollectedWithTheOriginalQueryAndAuthentication() {
        String first = java.util.stream.IntStream.rangeClosed(1, 200).mapToObj(id -> "{\"id\":" + id + "}")
                .collect(java.util.stream.Collectors.joining(","));
        server.expect(requestTo("http://paperless.test/api/documents/?text=late%20clause&fields=id&page_size=200"))
                .andExpect(header("Accept", PaperlessClient.ACCEPT_V10))
                .andExpect(header("Authorization", "Basic synthetic-test-token"))
                .andRespond(withSuccess("{\"count\":201,\"next\":\"http://paperless.test/api/documents/?text=late%20clause&fields=id&page_size=200&page=2\",\"results\":[" + first + "]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/?text=late%20clause&fields=id&page_size=200&page=2"))
                .andExpect(header("Accept", PaperlessClient.ACCEPT_V10))
                .andExpect(header("Authorization", "Basic synthetic-test-token"))
                .andRespond(withSuccess("{\"count\":201,\"next\":null,\"results\":[{\"id\":201}]}", MediaType.APPLICATION_JSON));
        var result = client.searchContent("late clause");
        assertThat(result.ids()).hasSize(201).contains(201);
        assertThat(result.state()).isEqualTo(dev.aegis.documents.application.api.ContentSearchState.READY);
        assertThat(result.archiveTotal()).isEqualTo(201L);
        server.verify();
    }

    @Test
    void failedLaterPageRetainsMatchesAndTitleFallbackCanBeUsed() {
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200"))
                .andRespond(withSuccess("{\"count\":2,\"next\":\"?text=strom&fields=id&page_size=200&page=2\",\"results\":[{\"id\":3}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200&page=2"))
                .andRespond(withServerError());
        var result = client.searchContent("strom");
        assertThat(result.ids()).containsExactly(3);
        assertThat(result.state()).isEqualTo(dev.aegis.documents.application.api.ContentSearchState.LIMITED);
        assertThat(result.limitation()).isEqualTo(dev.aegis.documents.application.api.SearchLimitation.ARCHIVE_FAILURE);
        assertThat(result.archiveTotal()).isEqualTo(2L);
        server.verify();
    }

    @Test
    void stalledResponseStopsAtTheOverallDeadlineAndCancelsItsWorker() throws Exception {
        var finished = new java.util.concurrent.CountDownLatch(1);
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200"))
                .andRespond(request -> {
                    try {
                        new java.util.concurrent.CountDownLatch(1).await(2, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                    return withSuccess("{\"count\":0,\"next\":null,\"results\":[]}", MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        var bounded = new PaperlessClient(transport, transport, JsonMapper.builder().build(),
                new ArchiveSearchPager(10, 2000, java.time.Duration.ofMillis(100), System::nanoTime));
        long start = System.nanoTime();
        var result = bounded.searchContent("strom");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(1));
        assertThat(result.state()).isEqualTo(dev.aegis.documents.application.api.ContentSearchState.UNAVAILABLE);
        assertThat(result.limitation()).isEqualTo(dev.aegis.documents.application.api.SearchLimitation.TIME_LIMIT);
        assertThat(finished.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void archiveFailureOnTheFirstPageIsUnavailable() {
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200"))
                .andRespond(withBadRequest());
        var result = client.searchContent("strom");
        assertThat(result.ids()).isEmpty();
        assertThat(result.state()).isEqualTo(dev.aegis.documents.application.api.ContentSearchState.UNAVAILABLE);
        assertThat(result.limitation()).isEqualTo(dev.aegis.documents.application.api.SearchLimitation.ARCHIVE_FAILURE);
    }

    @Test
    void searchAndProbeUseTheDocumentedEndpoints() {
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200"))
                .andRespond(withSuccess("{\"count\":2,\"next\":null,\"results\":[{\"id\":3},{\"id\":5}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/documents/?page_size=1&fields=id"))
                .andRespond(withSuccess().header("X-Version", "3.1.3"));

        assertThat(client.searchContent("strom").ids()).containsExactly(3, 5);
        var probe = client.probe();
        assertThat(probe.reachable()).isTrue();
        assertThat(probe.serverVersion()).isEqualTo("3.1.3");
    }
}
