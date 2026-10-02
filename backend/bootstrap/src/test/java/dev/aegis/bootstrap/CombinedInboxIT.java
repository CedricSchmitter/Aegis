package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.FakePaperlessArchive;
import dev.aegis.bootstrap.support.PostgresIT;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The combined inbox against PostgreSQL: notices that announce a review card are published but not listed as
 * messages, opening the inbox marks them read for that member only, the badge counts open cards plus unread messages,
 * a successful archival publishes no notice, and the migration retires stored "archived" notices without deleting
 * them.
 */
class CombinedInboxIT extends PostgresIT {
    private static final String RETIRE_MIGRATION =
            "db/migration/notifications/V4_3__retire_document_archived_notices.sql";

    @LocalServerPort
    int port;

    @Autowired
    FakePaperlessArchive archive;

    @Autowired
    JdbcClient jdbc;

    @Test
    void reviewCardNoticesAreNotListedAndOpeningTheInboxMarksThemReadForThatMemberOnly() {
        ApiClient anna = login(ANNA);
        ApiClient ben = login(BEN);
        String annaId = memberId(anna);
        // Opening the inbox first reconciles what earlier test classes left behind, so only this test's card is new.
        assertThat(anna.get("/api/reviews").status()).isEqualTo(200);
        long annaUnread = unread(anna);
        long benUnread = unread(ben);
        long open = openCards(anna);

        String documentId = uploadWithoutText(anna, "inbox");
        // Only a reconciliation turns the source into a card; the badge count itself never reconciles.
        assertThat(openCards(anna)).isEqualTo(open);

        JsonNode card = openCardFor(anna, documentId);
        String cardId = card.path("id").asText();
        assertThat(openCards(anna)).isEqualTo(open + 1);
        assertThat(openCards(ben)).isEqualTo(open + 1);
        // The card's notices exist for both members, unread, but are neither listed nor counted as messages.
        assertThat(reviewNoticeRead(annaId, cardId)).isFalse();
        for (ApiClient member : List.of(anna, ben)) {
            assertThat(linkIds(member)).doesNotContain(cardId);
        }
        assertThat(unread(anna)).isEqualTo(annaUnread);
        assertThat(unread(ben)).isEqualTo(benUnread);

        // A message arrives meanwhile: it is listed and counted, and opening the inbox leaves it unread.
        String benId = memberId(ben);
        ApiClient.Response task = ben.postJson("/api/tasks", "{\"title\":\"Eingang prüfen\",\"ownerId\":\"" + annaId
                + "\"}");
        assertThat(task.status()).as(task.body()).isEqualTo(201);
        String taskId = anna.json(task).path("id").asText();
        assertThat(unread(anna)).isEqualTo(annaUnread + 1);

        JsonNode marked = anna.json(anna.postJson("/api/notices/review-cards/read", "{}"));
        assertThat(marked.path("marked").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(reviewNoticeRead(annaId, cardId)).isTrue();
        assertThat(reviewNoticeRead(benId, cardId)).isFalse();
        assertThat(linkIds(anna)).contains(taskId);
        assertThat(unread(anna)).isEqualTo(annaUnread + 1);
        // Reading the notices decides nothing: the card stays open until a member dismisses or resolves it.
        assertThat(openCards(anna)).isEqualTo(open + 1);

        ApiClient.Response dismissed = anna.postJson("/api/reviews/" + cardId + "/dismiss",
                "{\"expectedVersion\":" + card.path("version").asLong() + "}");
        assertThat(dismissed.status()).as(dismissed.body()).isEqualTo(200);
        assertThat(openCards(ben)).isEqualTo(open);
    }

    @Test
    void anArchivalFailureIsShownAsItsCardAndASuccessfulArchivalNotifiesNobody() {
        ApiClient anna = login(ANNA);
        String annaId = memberId(anna);
        long annaUnread = unread(anna);

        String failedId;
        archive.available = false;
        try {
            byte[] pdf = ("%PDF-1.4\n% inbox failure " + System.nanoTime() + "\n").getBytes(StandardCharsets.US_ASCII);
            failedId = anna.json(anna.upload("/api/documents", "ausfall.pdf", pdf, List.of())).path("document")
                    .path("id").asText();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(anna.json(anna.get(
                    "/api/documents/" + failedId)).path("processing").path("state").asText()).isEqualTo("FAILED"));
        } finally {
            archive.available = true;
        }
        assertThat(noticeKinds(annaId, failedId)).containsExactly("DOCUMENT_ARCHIVAL_FAILED");
        assertThat(linkIds(anna)).doesNotContain(failedId);
        assertThat(unread(anna)).isEqualTo(annaUnread);

        JsonNode card = openCardFor(anna, failedId);
        assertThat(card.path("reason").asText()).isEqualTo("ARCHIVAL_FAILED");
        anna.json(anna.postJson("/api/notices/review-cards/read", "{}"));
        assertThat(jdbc.sql("SELECT count(*) FROM notices WHERE recipient_id = ? AND link_id = ? AND read_at IS NULL")
                .params(UUID.fromString(annaId), failedId).query(Long.class).single()).isZero();

        // The card's retry still works; the archival that follows publishes no "archived" notice.
        ApiClient.Response retried = anna.postJson("/api/reviews/" + card.path("id").asText()
                + "/actions/RETRY_ARCHIVAL", "{\"expectedVersion\":" + card.path("version").asLong() + "}");
        assertThat(retried.status()).as(retried.body()).isEqualTo(200);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(anna.json(anna.get(
                "/api/documents/" + failedId)).path("processing").path("state").asText()).isEqualTo("ARCHIVED"));
        assertThat(noticeKinds(annaId, failedId)).containsExactly("DOCUMENT_ARCHIVAL_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM notices WHERE kind = 'DOCUMENT_ARCHIVED' AND created_at > ?")
                .param(Timestamp.from(Instant.now().minus(Duration.ofMinutes(5)))).query(Long.class).single())
                .isZero();
    }

    @Test
    void theMigrationRetiresStoredArchivedNoticesWithoutDeletingThemOrTouchingOthers() throws IOException {
        ApiClient anna = login(ANNA);
        UUID annaId = UUID.fromString(memberId(anna));
        Instant earlier = Instant.parse("2026-09-20T08:00:00Z");
        Instant readBefore = Instant.parse("2026-09-21T08:00:00Z");
        UUID unreadArchived = insertNotice(annaId, "DOCUMENT_ARCHIVED", earlier, null);
        UUID readArchived = insertNotice(annaId, "DOCUMENT_ARCHIVED", earlier, readBefore);
        UUID unreadTask = insertNotice(annaId, "TASK_COMPLETED", earlier, null);
        // Even before the migration marks it read, a retired notice is no message.
        assertThat(linkIds(anna)).doesNotContain(unreadArchived.toString()).contains(unreadTask.toString());

        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '4.3'").query(Boolean.class)
                .single()).isTrue();
        // Run the migration's statement again, as on an installation that still has such notices.
        jdbc.sql(new ClassPathResource(RETIRE_MIGRATION).getContentAsString(StandardCharsets.UTF_8)).update();

        assertThat(readAt(unreadArchived)).isPresent();
        assertThat(readAt(readArchived)).contains(readBefore);
        assertThat(readAt(unreadTask)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM notices WHERE id IN (?, ?)").params(unreadArchived, readArchived)
                .query(Long.class).single()).isEqualTo(2);
        jdbc.sql("DELETE FROM notices WHERE id IN (?, ?, ?)").params(unreadArchived, readArchived, unreadTask)
                .update();
    }

    private UUID insertNotice(UUID recipient, String kind, Instant createdAt, Instant readAt) {
        UUID id = UUID.randomUUID();
        // The link id is the notice's own id so the listing can be checked for exactly this row.
        jdbc.sql("""
                INSERT INTO notices (id, recipient_id, kind, dedup_key, params_json, link_type, link_id, created_at,
                                     read_at)
                VALUES (?, ?, ?, ?, '{"title":"Synthetisch"}', 'DOCUMENT', ?, ?, ?)
                """).params(id, recipient, kind, "it-retire:" + id, id.toString(), Timestamp.from(createdAt),
                readAt == null ? null : Timestamp.from(readAt)).update();
        return id;
    }

    private Optional<Instant> readAt(UUID notice) {
        return jdbc.sql("SELECT read_at FROM notices WHERE id = ?").param(notice).query(Timestamp.class).optional()
                .map(Timestamp::toInstant);
    }

    private boolean reviewNoticeRead(String memberId, String cardId) {
        return jdbc.sql("SELECT read_at IS NOT NULL FROM notices WHERE recipient_id = ? AND dedup_key = ? "
                + "AND kind = 'REVIEW_ITEM'").params(UUID.fromString(memberId), "review:" + cardId)
                .query(Boolean.class).single();
    }

    private List<String> noticeKinds(String memberId, String documentId) {
        return jdbc.sql("SELECT kind FROM notices WHERE recipient_id = ? AND link_id = ? ORDER BY created_at")
                .params(UUID.fromString(memberId), documentId).query(String.class).list();
    }

    private static List<String> linkIds(ApiClient client) {
        return StreamSupport.stream(client.json(client.get("/api/notices")).spliterator(), false)
                .map(notice -> notice.path("linkId").asText()).toList();
    }

    private static long unread(ApiClient client) {
        return client.json(client.get("/api/notices/unread-count")).path("unread").asLong();
    }

    private static long openCards(ApiClient client) {
        return client.json(client.get("/api/reviews/open-count")).path("open").asLong();
    }

    private static String memberId(ApiClient client) {
        return client.json(client.get("/api/auth/session")).path("member").path("id").asText();
    }

    /** Opens the inbox, which reconciles, and returns the open card about the document. */
    private static JsonNode openCardFor(ApiClient client, String documentId) {
        JsonNode cards = client.json(client.get("/api/reviews?status=OPEN"));
        return StreamSupport.stream(cards.spliterator(), false)
                .filter(card -> documentId.equals(card.path("sourceId").asText())).findFirst().orElseThrow();
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    /** Uploads a photo in which the archive recognizes no text and waits until that was measured (ADR-0012). */
    private String uploadWithoutText(ApiClient client, String label) {
        byte[] photo = uniqueJpeg(label);
        archive.ocrText(photo, " ");
        ApiClient.Response uploaded = client.upload("/api/documents", "IMG_" + System.nanoTime() + ".jpg", photo,
                List.of("HOUSE"));
        assertThat(uploaded.status()).as(uploaded.body()).isEqualTo(201);
        String id = client.json(uploaded).path("document").path("id").asText();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(client.json(client.get(
                "/api/documents/" + id)).path("textRecognition").path("state").asText()).isEqualTo("NONE"));
        return id;
    }

    private static byte[] uniqueJpeg(String label) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0,
                1, 1, 0, 0, 72, 0, 72, 0, 0});
        byte[] comment = (label + " " + System.nanoTime()).getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        out.writeBytes(comment);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }
}
