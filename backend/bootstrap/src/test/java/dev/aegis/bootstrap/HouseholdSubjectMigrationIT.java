package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.HouseholdDataWipe;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.documents.application.api.AnalysisView;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.DocumentAnalysisCommands;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.MigrateHouseholdSubjects;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The retired subject "both" ({@code HOUSEHOLD}) becomes one subject per person, both in the stored data at startup
 * and in a restore of an export written before schema 9 (issue #83, ADR-0017). The assistant account {@code helper}
 * is a member but never one of the persons.
 */
class HouseholdSubjectMigrationIT extends PostgresIT {
    @Autowired
    UploadDocument upload;
    @Autowired
    DocumentQueries documents;
    @Autowired
    CategoryCommands categories;
    @Autowired
    CatalogQueries catalog;
    @Autowired
    ClassificationCommands classification;
    @Autowired
    DocumentAnalysisCommands analysis;
    @Autowired
    MigrateHouseholdSubjects migration;
    @Autowired
    CreateExport createExport;
    @Autowired
    RestoreExport restore;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    FakePaperlessArchive archive;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Test
    void storedBothBecomesEachPersonWithoutDuplicatesAndWithoutTheAssistant() {
        Actor anna = actor(ANNA);
        MemberId annaId = anna.memberId();
        MemberId benId = actor(BEN).memberId();
        DocumentId house = upload(anna, "Grundsteuer", new SubjectView("HOUSE", null));
        DocumentId withBen = upload(anna, "Kfz Ben", new SubjectView("MEMBER", benId));
        DocumentId filed = upload(anna, "Ohne Zuordnung");
        DocumentId withAnna = upload(anna, "Arzt Anna", new SubjectView("MEMBER", annaId));
        CategoryView folder = categories.create(anna, new CreateCategory(null, null, "Familie " + System.nanoTime()));
        classification.place(anna, filed, folder.id(), documents.get(anna, filed).classification().version());
        // Archival changes record versions; wait for it so that only the migration could change them below.
        awaitArchived(anna, house, withBen, filed, withAnna);

        // State as the previous release stored it: "both" on documents, on a folder and in analysis suggestions.
        jdbc.update("INSERT INTO document_subjects (document_id, kind) VALUES (?, 'HOUSEHOLD'), (?, 'HOUSEHOLD')",
                house.value(), withBen.value());
        jdbc.update("INSERT INTO category_implied_subjects (category_id, kind) VALUES (?, 'HOUSEHOLD')", folder.id());
        jdbc.update("INSERT INTO category_subject_effects (category_id, kind) VALUES (?, 'HOUSEHOLD')", folder.id());
        suggestions(withBen, "[" + suggestion("HOUSEHOLD", null, "ACCEPTED", annaId) + ","
                + suggestion("MEMBER", benId, "REJECTED", annaId) + "]");
        suggestions(filed, "[" + suggestion("HOUSEHOLD", null, "SUGGESTED", null) + "]");
        suggestions(withAnna, "[" + suggestion("HOUSEHOLD", null, "SUGGESTED", null) + "]");
        List<Long> versionsBefore = versions(house, withBen, filed, withAnna);
        long folderVersion = folderVersion(folder.id());

        MigrateHouseholdSubjects.Result result = migration.migrateHouseholdSubjects();

        assertThat(result).isEqualTo(new MigrateHouseholdSubjects.Result(true, 2, 2, 1, 3));
        SubjectView annaSubject = new SubjectView("MEMBER", annaId);
        SubjectView benSubject = new SubjectView("MEMBER", benId);
        assertThat(documents.get(anna, house).subjects()).containsExactlyInAnyOrder(new SubjectView("HOUSE", null),
                annaSubject, benSubject);
        assertThat(documents.get(anna, withBen).subjects()).containsExactlyInAnyOrder(benSubject, annaSubject);
        assertThat(documents.get(anna, filed).subjects()).isEmpty();
        assertThat(documents.get(anna, withAnna).subjects()).containsExactly(annaSubject);
        assertThat(catalog.categories(anna).categories()).filteredOn(view -> view.id().equals(folder.id()))
                .singleElement().satisfies(view -> assertThat(view.impliedSubjects())
                        .containsExactlyInAnyOrder(annaSubject, benSubject));
        assertThat(documents.get(anna, filed).classification().folderSubjects())
                .containsExactlyInAnyOrder(annaSubject, benSubject);

        // Decisions survive, an explicit value for a person wins, and a pending suggestion is not repeated for a
        // person the document already names.
        assertThat(subjectSuggestions(anna, withBen)).containsExactly(tuple(annaSubject, "ACCEPTED"),
                tuple(benSubject, "REJECTED"));
        assertThat(subjectSuggestions(anna, filed)).containsExactlyInAnyOrder(tuple(annaSubject, "SUGGESTED"),
                tuple(benSubject, "SUGGESTED"));
        assertThat(subjectSuggestions(anna, withAnna)).containsExactly(tuple(benSubject, "SUGGESTED"));

        // Searching for one person now finds what concerned both, including through the folder.
        ApiClient lisa = login(ANNA);
        assertThat(ids(lisa, "/api/documents?subject=MEMBER:" + annaId)).contains(house.toString(),
                withBen.toString(), filed.toString(), withAnna.toString());
        assertThat(ids(lisa, "/api/documents?subject=MEMBER:" + benId)).contains(house.toString(),
                withBen.toString(), filed.toString()).doesNotContain(withAnna.toString());
        assertThat(ids(lisa, "/api/documents?subject=MEMBER:" + actor(HELPER).memberId())).isEmpty();

        // Record versions are unchanged, so open review cards are not renewed; a second run finds nothing.
        assertThat(versions(house, withBen, filed, withAnna)).isEqualTo(versionsBefore);
        assertThat(folderVersion(folder.id())).isEqualTo(folderVersion);
        assertThat(migration.migrateHouseholdSubjects()).isEqualTo(MigrateHouseholdSubjects.Result.NOTHING_TO_MIGRATE);
        assertThat(jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM document_subjects WHERE kind = 'HOUSEHOLD')
                     + (SELECT count(*) FROM category_implied_subjects WHERE kind = 'HOUSEHOLD')
                     + (SELECT count(*) FROM category_subject_effects WHERE kind = 'HOUSEHOLD')
                     + (SELECT count(*) FROM document_analyses WHERE subjects @> '[{"kind": "HOUSEHOLD"}]')""",
                Long.class)).isZero();
    }

    @Test
    void restoringAnExportBeforeSchema9MapsBothTheSameWay() throws Exception {
        Actor anna = actor(ANNA);
        MemberId annaId = anna.memberId();
        MemberId benId = actor(BEN).memberId();
        DocumentId letter = upload(anna, "Nebenkosten", new SubjectView("HOUSE", null));
        DocumentId note = upload(anna, "Werkstatt", new SubjectView("MEMBER", benId));
        CategoryView folder = categories.create(anna, new CreateCategory(null, null, "Wohnung " + System.nanoTime()));
        // Gives the second document an analysis record, so the export carries one to rewrite.
        analysis.setFilename(anna, note, "Werkstatt Rechnung", documents.get(anna, note).analysis().version());
        ExportSummary export = createExport.export(anna);
        Path location = Path.of(export.location());
        String benExported = benId.toString();

        // Rewrite the generation into what schema 8 wrote: "both" on the document, the folder and a pending
        // suggestion, and members without a kind. The manifest checksums are recomputed like the exporter does.
        rewrite(location, "data/documents.json", root -> {
            for (JsonNode entry : root.path("documents")) {
                if (entry.path("id").asText().equals(letter.toString())) {
                    ArrayNode subjects = ((ObjectNode) entry).putArray("subjects");
                    subjects.addObject().put("kind", "HOUSEHOLD").putNull("memberId");
                    subjects.addObject().put("kind", "MEMBER").put("memberId", benExported);
                }
                if (entry.path("id").asText().equals(note.toString())) {
                    ArrayNode suggested = ((ObjectNode) entry.path("analysis")).putArray("subjects");
                    ObjectNode both = suggested.addObject();
                    both.putObject("subject").put("kind", "HOUSEHOLD").putNull("memberId");
                    both.put("confidence", 0.7).put("evidence", "Mieter").put("decision", "SUGGESTED");
                }
            }
        });
        rewrite(location, "data/catalog.json", root -> {
            for (JsonNode entry : root.path("folders")) {
                if (entry.path("id").asText().equals(folder.id().toString())) {
                    ((ObjectNode) entry).putArray("impliedSubjects").addObject().put("kind", "HOUSEHOLD")
                            .putNull("memberId");
                }
            }
        });
        rewrite(location, "data/members.json", root -> root.path("members")
                .forEach(member -> ((ObjectNode) member).remove("kind")));
        rewrite(location, "manifest.json", root -> ((ObjectNode) root).put("schemaVersion", 8));
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readAllBytes(location.resolve("manifest.json")));
        ObjectNode checksums = (ObjectNode) manifest.path("fileChecksumsSha256");
        for (String file : List.of("data/documents.json", "data/catalog.json", "data/members.json")) {
            checksums.put(file, sha256(Files.readAllBytes(location.resolve(file))));
        }
        Files.write(location.resolve("manifest.json"), json.writeValueAsBytes(manifest));

        wipeHouseholdData();
        RestoreExport.RestoreReport report = restore.restore(anna, export.location());

        assertThat(report.documentsImported()).isEqualTo(export.documentCount());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                documents.get(anna, letter).processing().state()).isEqualTo("ARCHIVED"));
        SubjectView annaSubject = new SubjectView("MEMBER", annaId);
        SubjectView benSubject = new SubjectView("MEMBER", benId);
        assertThat(documents.get(anna, letter).subjects()).containsExactlyInAnyOrder(annaSubject, benSubject);
        assertThat(documents.get(anna, note).subjects()).containsExactly(benSubject);
        // The pending "both" is only still open for the person the document does not name yet.
        assertThat(subjectSuggestions(anna, note)).containsExactly(tuple(annaSubject, "SUGGESTED"));
        assertThat(catalog.categories(anna).categories()).filteredOn(view -> view.id().equals(folder.id()))
                .singleElement().satisfies(view -> assertThat(view.impliedSubjects())
                        .containsExactlyInAnyOrder(annaSubject, benSubject));
        // New exports name the kind of every member and no longer contain "both".
        ExportSummary fresh = createExport.export(anna);
        JsonNode freshMembers = json.readTree(Files.readAllBytes(Path.of(fresh.location())
                .resolve("data/members.json")));
        assertThat(freshMembers.path("members")).extracting(member -> member.path("username").asText() + ":"
                + member.path("kind").asText()).contains("anna:PERSON", "ben:PERSON", "helper:ASSISTANT");
        assertThat(json.readTree(Files.readAllBytes(Path.of(fresh.location()).resolve("manifest.json")))
                .path("schemaVersion").asInt()).isEqualTo(9);
        assertThat(Files.readString(Path.of(fresh.location()).resolve("data/documents.json")))
                .doesNotContain("HOUSEHOLD");
    }

    private Actor actor(String username) {
        return new Actor(members.findCredentials(username).orElseThrow().id());
    }

    private DocumentId upload(Actor actor, String title, SubjectView... subjects) {
        byte[] pdf = ("%PDF-1.4 synthetic " + title + " " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        return upload.upload(actor, new UploadCommand(title + ".pdf", "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf), List.of(subjects))).document().id();
    }

    private void awaitArchived(Actor actor, DocumentId... ids) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (DocumentId id : ids) {
                assertThat(documents.get(actor, id).processing().state()).isEqualTo("ARCHIVED");
            }
        });
    }

    private void suggestions(DocumentId document, String subjects) {
        jdbc.update("""
                INSERT INTO document_analyses (document_id, status, subjects) VALUES (?, 'NOT_REQUESTED', ?::jsonb)
                ON CONFLICT (document_id) DO UPDATE SET subjects = EXCLUDED.subjects""", document.value(), subjects);
    }

    private static String suggestion(String kind, MemberId member, String decision, MemberId decidedBy) {
        return """
                {"kind": "%s", "memberId": %s, "confidence": 0.8, "evidence": "Familie", "decision": "%s",
                 "decidedBy": %s, "decidedAt": %s}""".formatted(kind, member == null ? "null" : "\"" + member + "\"",
                decision, decidedBy == null ? "null" : "\"" + decidedBy + "\"",
                decidedBy == null ? "null" : "\"2026-09-20T08:00:00Z\"");
    }

    private List<org.assertj.core.groups.Tuple> subjectSuggestions(Actor actor, DocumentId document) {
        List<org.assertj.core.groups.Tuple> result = new ArrayList<>();
        for (AnalysisView.SubjectSuggestion suggestion : documents.get(actor, document).analysis().subjects()) {
            result.add(tuple(suggestion.subject(), suggestion.decision()));
        }
        return result;
    }

    private List<Long> versions(DocumentId... ids) {
        List<Long> versions = new ArrayList<>();
        for (DocumentId id : ids) {
            versions.add(jdbc.queryForObject("SELECT version FROM documents WHERE id = ?", Long.class, id.value()));
            versions.addAll(jdbc.queryForList("SELECT version FROM document_analyses WHERE document_id = ?",
                    Long.class, id.value()));
        }
        return versions;
    }

    private Long folderVersion(UUID folder) {
        return jdbc.queryForObject("SELECT version FROM categories WHERE id = ?", Long.class, folder);
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static List<String> ids(ApiClient client, String path) {
        ApiClient.Response response = client.get(path);
        assertThat(response.status()).as(path + " -> " + response.body()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        client.json(response).path("items").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private void rewrite(Path location, String file, java.util.function.Consumer<JsonNode> change) throws Exception {
        JsonNode root = json.readTree(Files.readAllBytes(location.resolve(file)));
        change.accept(root);
        Files.write(location.resolve(file), json.writeValueAsBytes(root));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Like RestoreIT: household data goes, members stay, as on a fresh instance with the same usernames. */
    private void wipeHouseholdData() throws Exception {
        HouseholdDataWipe.truncate(jdbc);
        try (var files = Files.list(DATA.resolve("ingress"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        archive.clear();
    }
}
