package dev.aegis.documents.adapters.paperless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://paperless.test")
                .defaultHeader("Accept", PaperlessClient.ACCEPT_V10);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaperlessClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void submitReturnsTaskIdFromQuotedResponse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.pdf");
        Files.write(file, "%PDF-x".getBytes());
        server.expect(requestTo("http://paperless.test/api/documents/post_document/")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", PaperlessClient.ACCEPT_V10))
                .andRespond(withSuccess("\"7d3d1b8a-1111-2222-3333-444455556666\"", MediaType.APPLICATION_JSON));

        var outcome = client.submit(file, "Title", "x.pdf");

        assertThat(outcome).isEqualTo(new PaperlessArchive.Accepted("7d3d1b8a-1111-2222-3333-444455556666"));
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
    void searchAndProbeUseTheDocumentedEndpoints() {
        server.expect(requestTo("http://paperless.test/api/documents/?text=strom&fields=id&page_size=200"))
                .andRespond(withSuccess("{\"count\":2,\"results\":[{\"id\":3},{\"id\":5}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://paperless.test/api/")).andRespond(withSuccess().header("X-Version", "3.1.3"));

        assertThat(client.searchContent("strom")).containsExactly(3, 5);
        var probe = client.probe();
        assertThat(probe.reachable()).isTrue();
        assertThat(probe.serverVersion()).isEqualTo("3.1.3");
    }
}
