package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.LabelView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.shared.Actor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Measures search latency at a representative household volume: 10,000 documents across 40 folders with type,
 * organization, topic and subject values. The bound is generous (CI machines vary); the point is that filtering,
 * counting and paging run in PostgreSQL and stay usable, and that a regression into per-row work is visible.
 */
class CatalogSearchPerformanceIT extends PostgresIT {
    private static final int DOCUMENTS = 10_000;
    private static final int FOLDERS = 40;
    private static final long BUDGET_MILLIS = 2_000;
    private static final String TITLE_PREFIX = "Perf ";

    @Autowired
    DocumentQueries documents;
    @Autowired
    CategoryCommands categories;
    @Autowired
    CatalogQueries catalog;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcTemplate jdbc;

    private static JdbcTemplate cleanup;

    @AfterAll
    static void removeSeededDocuments() {
        if (cleanup != null) {
            cleanup.update("DELETE FROM documents WHERE title LIKE ?", TITLE_PREFIX + "%");
            cleanup.update("DELETE FROM categories WHERE name LIKE ?", "Perf folder%");
        }
    }

    @Test
    void searchAndFacetCountsStayFastWithTenThousandDocuments() {
        cleanup = jdbc;
        Actor lisa = new Actor(members.findCredentials(ANNA).orElseThrow().id());
        List<LabelView> labels = catalog.labels(lisa);
        UUID invoice = builtin(labels, "invoice");
        UUID contract = builtin(labels, "contract");
        UUID internet = builtin(labels, "internet");
        UUID water = builtin(labels, "water");
        List<UUID> folders = new ArrayList<>();
        for (int index = 0; index < FOLDERS; index++) {
            CategoryView folder = categories.create(lisa, new CreateCategory(null, null, "Perf folder " + index));
            categories.changeImplications(lisa, folder.id(), List.of(index % 2 == 0 ? invoice : contract),
                    List.of(new SubjectView("HOUSE", null)), folder.version());
            folders.add(folder.id());
        }
        seed(folders, List.of(internet, water));

        long listing = measure(() -> documents.search(lisa, new DocumentSearchRequest(null, null, true, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), false, false, Set.of(), "NEWEST", 0, 25)));
        DocumentSearchResult byFacets = documents.search(lisa, new DocumentSearchRequest(null, null, true, false,
                List.of(invoice), List.of(), List.of(internet), List.of(), List.of(new SubjectView("HOUSE", null)),
                false, false, Set.of(), "NEWEST", 0, 25));
        long faceted = measure(() -> documents.search(lisa, new DocumentSearchRequest(null, null, true, false,
                List.of(invoice), List.of(), List.of(internet), List.of(), List.of(new SubjectView("HOUSE", null)),
                false, false, Set.of(), "NEWEST", 0, 25)));
        long scoped = measure(() -> documents.search(lisa, new DocumentSearchRequest("Perf", folders.get(0), true,
                false, List.of(), List.of(), List.of(), List.of(), List.of(), false, false, Set.of(), "TITLE", 2,
                25)));
        long missing = measure(() -> documents.search(lisa, new DocumentSearchRequest(null, null, true, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), false, false, Set.of("TAG"), "NEWEST", 0,
                25)));

        System.out.printf("catalog search latency with %d documents: listing %d ms, facets %d ms, "
                + "folder+text %d ms, missing %d ms%n", DOCUMENTS, listing, faceted, scoped, missing);
        assertThat(byFacets.items()).hasSize(25);
        assertThat(byFacets.matchingDocuments()).isGreaterThan(1_000);
        assertThat(byFacets.facets().values().get("DOCUMENT_TYPE")).isNotEmpty();
        assertThat(List.of(listing, faceted, scoped, missing))
                .allSatisfy(elapsed -> assertThat(elapsed).isLessThan(BUDGET_MILLIS));
    }

    private static UUID builtin(List<LabelView> labels, String key) {
        return labels.stream().filter(label -> key.equals(label.builtinKey())).map(LabelView::id).findFirst()
                .orElseThrow();
    }

    private long measure(Runnable query) {
        query.run();
        long start = System.nanoTime();
        query.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void seed(List<UUID> folders, List<UUID> topics) {
        Instant uploadedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID uploader = members.findCredentials(ANNA).orElseThrow().id().value();
        List<Object[]> documentRows = new ArrayList<>();
        List<Object[]> classificationRows = new ArrayList<>();
        List<Object[]> labelRows = new ArrayList<>();
        List<Object[]> subjectRows = new ArrayList<>();
        for (int index = 0; index < DOCUMENTS; index++) {
            UUID id = UUID.randomUUID();
            UUID folder = folders.get(index % folders.size());
            documentRows.add(new Object[] {id, TITLE_PREFIX + index, "perf-" + index + ".pdf", "application/pdf",
                    1024L, id.toString().replace("-", "").repeat(2), uploader,
                    java.sql.Timestamp.from(uploadedAt.plusSeconds(index)), "perf/" + id + ".pdf", "ARCHIVED",
                    100_000 + index});
            classificationRows.add(new Object[] {id, folder, uploader,
                    java.sql.Timestamp.from(uploadedAt.plusSeconds(index))});
            labelRows.add(new Object[] {id, topics.get(index % topics.size()), "MANUAL", "CONFIRMED",
                    java.sql.Timestamp.from(uploadedAt.plusSeconds(index))});
            subjectRows.add(new Object[] {id, index % 3 == 0 ? "HOUSEHOLD" : "HOUSE"});
        }
        jdbc.batchUpdate("""
                INSERT INTO documents (id, title, original_filename, content_type, size_bytes, checksum_sha256,
                       uploaded_by, uploaded_at, staging_path, staging_present, state, attempts,
                       paperless_document_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, 0, ?, 0)""", documentRows);
        jdbc.batchUpdate("""
                INSERT INTO document_classifications (document_id, category_id, updated_by, updated_at, version)
                VALUES (?, ?, ?, ?, 1)""", classificationRows);
        jdbc.batchUpdate("""
                INSERT INTO document_labels (document_id, label_id, origin, review_state, decided_at)
                VALUES (?, ?, ?, ?, ?)""", labelRows);
        jdbc.batchUpdate("INSERT INTO document_subjects (document_id, kind) VALUES (?, ?)", subjectRows);
        // Bulk-inserted fixture data has no statistics yet, so the planner would size these tables from their
        // physical width instead of their content and could pick a plan no production instance ever uses
        // (autovacuum analyzes there). Measuring without this would measure the fixture, not the queries.
        jdbc.execute("ANALYZE documents, document_classifications, document_labels, document_subjects");
    }
}
