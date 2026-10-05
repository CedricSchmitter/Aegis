package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.adapters.paperless.PaperlessClient;
import dev.aegis.documents.application.api.*;
import dev.aegis.documents.application.ports.ArchiveSearchResult;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Controlled HTTP pagination into the real PostgreSQL catalog and REST contract, with synthetic documents only. */
class ArchiveRetrievalIT extends PostgresIT {
    private static final String QUERY = "synthetic-retrieval159";
    private static final String URL = "http://archive.test/api/documents/?text=" + QUERY + "&fields=id&page_size=200";
    @Autowired JdbcTemplate jdbc;
    @Autowired FakePaperlessArchive archive;
    @Autowired CategoryCommands categories;
    @Autowired CatalogQueries catalog;
    @Autowired MemberCredentialsQuery members;
    @LocalServerPort int port;

    @Test
    void laterContentOnlySourceSurvivesFiltersCountsPermissionsAndTrash() {
        Actor actor = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        UUID folder = categories.create(actor, new CategoryCommands.CreateCategory(null, null, "Retrieval159")).id();
        UUID invoice = catalog.labels(actor).stream().filter(l -> "invoice".equals(l.builtinKey()))
                .findFirst().orElseThrow().id();
        List<UUID> ids = new ArrayList<>();
        try {
            seed(ids, actor, folder, invoice);
            archive.searchResults.put(QUERY, collected());
            String query = "/api/documents?query=" + QUERY + "&category=" + folder + "&type=" + invoice
                    + "&subject=house&sort=TITLE";
            assertThat(new ApiClient(port).get(query).status()).isEqualTo(401);
            for (String member : List.of(ANNA, BEN)) {
                ApiClient api = new ApiClient(port); assertThat(api.login(member, PASSWORD)).isTrue();
                var result = api.json(api.get(query));
                assertThat(result.path("items")).hasSize(1);
                assertThat(result.path("items").get(0).path("id").asString()).isEqualTo(ids.get(200).toString());
                assertThat(result.path("contentSearch").asString()).isEqualTo("READY");
                assertThat(result.path("countAccuracy").asString()).isEqualTo("EXACT");
                assertThat(result.path("archiveTotal").asLong()).isEqualTo(500);
                assertThat(result.path("matchingDocuments").asLong()).isEqualTo(1);
                assertThat(result.path("facets").path("values").path("DOCUMENT_TYPE").path(invoice.toString())
                        .asLong()).isEqualTo(1);
            }
            ApiClient api = new ApiClient(port); assertThat(api.login(ANNA, PASSWORD)).isTrue();
            archive.searchResults.put(QUERY, new ArchiveSearchResult(IntStream.rangeClosed(900001, 900200).boxed()
                    .toList(), ContentSearchState.LIMITED, SearchLimitation.ARCHIVE_FAILURE, 500L));
            var limited = api.json(api.get(query));
            assertThat(limited.path("items")).isEmpty();
            assertThat(limited.path("matchingDocuments").asLong()).isZero();
            assertThat(limited.path("countAccuracy").asString()).isEqualTo("LOWER_BOUND");
            assertThat(limited.path("searchLimitation").asString()).isEqualTo("ARCHIVE_FAILURE");
            archive.searchResults.put(QUERY, new ArchiveSearchResult(List.of(), ContentSearchState.UNAVAILABLE,
                    SearchLimitation.ARCHIVE_FAILURE, null));
            var fallback = api.json(api.get("/api/documents?query=" + QUERY));
            assertThat(fallback.path("items")).hasSize(1);
            assertThat(fallback.path("contentSearch").asString()).isEqualTo("UNAVAILABLE");
            assertThat(fallback.path("countAccuracy").asString()).isEqualTo("LOWER_BOUND");
            archive.searchResults.put(QUERY, collected());
            jdbc.update("UPDATE documents SET trashed_by=?, trashed_at=now(), restorable_until=now()+interval '30 days' WHERE id=?",
                    actor.memberId().value(), ids.get(200));
            var trashed = api.json(api.get(query));
            assertThat(trashed.path("items")).isEmpty();
            assertThat(trashed.path("matchingDocuments").asLong()).isZero();
        } finally {
            archive.searchResults.remove(QUERY);
            ids.forEach(id -> jdbc.update("DELETE FROM documents WHERE id=?", id));
            jdbc.update("DELETE FROM categories WHERE id=?", folder);
        }
    }

    private ArchiveSearchResult collected() {
        var builder = RestClient.builder().baseUrl("http://archive.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        for (int page = 1; page <= 3; page++) {
            int last = Math.min(page * 200, 500);
            String results = IntStream.rangeClosed(900000 + (page - 1) * 200 + 1, 900000 + last)
                    .mapToObj(id -> "{\"id\":" + id + "}").collect(java.util.stream.Collectors.joining(","));
            String next = page == 3 ? "null" : "\"" + URL + "&page=" + (page + 1) + "\"";
            server.expect(requestTo(URL + (page == 1 ? "" : "&page=" + page)))
                    .andRespond(withSuccess("{\"count\":500,\"next\":" + next + ",\"results\":[" + results + "]}",
                            MediaType.APPLICATION_JSON));
        }
        var result = new PaperlessClient(builder.build(), JsonMapper.builder().build()).searchContent(QUERY);
        server.verify(); return result;
    }

    private void seed(List<UUID> ids, Actor actor, UUID folder, UUID invoice) {
        Timestamp at = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        List<Object[]> rows = new ArrayList<>();
        for (int index = 1; index <= 500; index++) {
            UUID id = UUID.randomUUID(); ids.add(id);
            rows.add(new Object[]{id, index == 1 ? QUERY + " title" : "Synthetic scan " + index,
                    "synthetic-" + index + ".pdf", "application/pdf", 1024L,
                    id.toString().replace("-", "").repeat(2), actor.memberId().value(), at, "synthetic/" + id,
                    900000 + index});
        }
        jdbc.batchUpdate("""
                INSERT INTO documents (id,title,original_filename,content_type,size_bytes,checksum_sha256,
                    uploaded_by,uploaded_at,staging_path,staging_present,state,attempts,paperless_document_id,version)
                VALUES (?,?,?,?,?,?,?,?,?,false,'ARCHIVED',0,?,0)""", rows);
        jdbc.update("INSERT INTO document_classifications (document_id,category_id,updated_by,updated_at,version) VALUES (?,?,?,?,1)",
                ids.get(200), folder, actor.memberId().value(), at);
        jdbc.update("INSERT INTO document_labels (document_id,label_id,origin,review_state,decided_at) VALUES (?,?,'MANUAL','CONFIRMED',?)",
                ids.get(200), invoice, at);
        jdbc.update("INSERT INTO document_subjects (document_id,kind,member_id) VALUES (?,'HOUSE',null)", ids.get(200));
    }
}
