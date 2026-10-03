package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.bootstrap.support.RecordingPushSender;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.notifications.application.api.NoticeDraft;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.notifications.application.api.PushDispatch;
import dev.aegis.notifications.application.push.PushSender;
import dev.aegis.shared.MemberId;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Web Push (issue #28) through the REST API against PostgreSQL, with the push service replaced by a recording sender:
 * the server key persists, a member's notice reaches the member's device once, preferences are versioned and devices
 * stay private to their member.
 */
class PushIT extends PostgresIT {
    @LocalServerPort
    int port;
    @Autowired
    RecordingPushSender sender;
    @Autowired
    PushDispatch dispatch;
    @Autowired
    PublishNotice notices;
    @Autowired
    MemberCredentialsQuery members;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        sender.reset();
        jdbc.sql("DELETE FROM push_subscriptions").update();
        jdbc.sql("DELETE FROM push_preferences").update();
        jdbc.sql("DELETE FROM notices WHERE dedup_key LIKE 'push-it:%'").update();
    }

    private ApiClient loggedIn(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }

    private static String subscription(String path) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] point = new byte[65];
        point[0] = 0x04;
        copy(key.getW().getAffineX().toByteArray(), point, 1);
        copy(key.getW().getAffineY().toByteArray(), point, 33);
        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        return """
                {"endpoint":"https://fcm.googleapis.com/fcm/send/%s","p256dh":"%s","auth":"%s","deviceLabel":"Pixel"}
                """.formatted(path, base64url.encodeToString(point), base64url.encodeToString(new byte[16]));
    }

    private static void copy(byte[] unsigned, byte[] target, int offset) {
        int length = Math.min(unsigned.length, 32);
        System.arraycopy(unsigned, unsigned.length - length, target, offset + 32 - length, length);
    }

    private MemberId memberId(String username) {
        return members.findCredentials(username).orElseThrow().id();
    }

    private void publish(String username, String kind) {
        notices.publish(new NoticeDraft(memberId(username), kind, "push-it:" + UUID.randomUUID(),
                Map.of("title", "Steuererklärung"), "TASK", UUID.randomUUID().toString()));
    }

    @Test
    void aNoticeReachesTheMembersSubscribedDeviceOnceAndTheServerKeyPersists() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        JsonNode config = anna.json(anna.get("/api/push/config"));
        assertThat(config.path("available").asBoolean()).isTrue();
        String key = config.path("publicKey").asText();
        assertThat(Base64.getUrlDecoder().decode(key)).hasSize(65);
        assertThat(loggedIn(BEN).json(loggedIn(BEN).get("/api/push/config")).path("publicKey").asText())
                .isEqualTo(key);
        assertThat(jdbc.sql("SELECT public_key FROM push_server_keys").query(String.class).single()).isEqualTo(key);

        // No quiet hours, so the test does not depend on the time of day it runs at.
        assertThat(anna.putJson("/api/push/preferences", "{\"categories\":[\"TASKS\",\"DEADLINES\",\"REVIEW\","
                + "\"SYSTEM\"],\"quietStart\":null,\"quietEnd\":null,\"showDetails\":false,\"version\":0}")
                .status()).isEqualTo(200);
        ApiClient.Response subscribed = anna.postJson("/api/push/devices", subscription("anna-phone"));
        assertThat(subscribed.status()).as(subscribed.body()).isEqualTo(200);
        String device = anna.json(subscribed).path("id").asText();

        publish(ANNA, "TASK_ASSIGNED");
        publish(BEN, "TASK_ASSIGNED");
        assertThat(dispatch.dispatchPending()).isEqualTo(new PushDispatch.Report(1, 0, 0));

        assertThat(sender.sent).singleElement().satisfies(sent -> {
            assertThat(sent.endpoint()).endsWith("/anna-phone");
            assertThat(sent.message().body()).isEqualTo("Dir wurde eine Aufgabe zugewiesen.");
            assertThat(sent.message().path()).startsWith("/tasks/");
        });
        assertThat(dispatch.dispatchPending()).isEqualTo(new PushDispatch.Report(0, 0, 0));
        assertThat(jdbc.sql("SELECT state FROM push_deliveries WHERE subscription_id = ?::uuid").param(device)
                .query(String.class).list()).containsExactly("SENT");
        assertThat(anna.json(anna.get("/api/push/devices"))).singleElement()
                .satisfies(d -> assertThat(d.path("lastSentAt").isNull()).isFalse());

        sender.answer(PushSender.Outcome.GONE, 410);
        ApiClient.Response test = anna.postJson("/api/push/devices/" + device + "/test", null);
        assertThat(test.status()).as(test.body()).isEqualTo(200);
        assertThat(anna.json(test).path("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(anna.json(anna.get("/api/push/devices")).get(0).path("state").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void devicesArePrivateAndPreferencesAreVersioned() throws Exception {
        ApiClient anna = loggedIn(ANNA);
        ApiClient ben = loggedIn(BEN);
        String device = anna.json(anna.postJson("/api/push/devices", subscription("anna-tablet"))).path("id")
                .asText();

        assertThat(ben.json(ben.get("/api/push/devices"))).isEmpty();
        assertThat(ben.delete("/api/push/devices/" + device).status()).isEqualTo(404);
        assertThat(ben.postJson("/api/push/devices/" + device + "/test", null).status()).isEqualTo(404);
        assertThat(new ApiClient(port).get("/api/push/devices").status()).isIn(401, 403);

        ApiClient.Response internal = ben.postJson("/api/push/devices",
                subscription("x").replace("https://fcm.googleapis.com", "https://10.0.0.1"));
        assertThat(internal.status()).isEqualTo(400);
        assertThat(ben.json(internal).path("code").asText()).isEqualTo("push.endpoint.notAllowed");

        JsonNode defaults = anna.json(anna.get("/api/push/preferences"));
        assertThat(defaults.path("quietStart").asText()).startsWith("22:00");
        assertThat(defaults.path("version").asLong()).isZero();
        ApiClient.Response changed = anna.putJson("/api/push/preferences",
                "{\"categories\":[\"TASKS\"],\"quietStart\":null,\"quietEnd\":null,\"showDetails\":true,\"version\":0}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(anna.json(changed).path("version").asLong()).isEqualTo(1);
        ApiClient.Response stale = anna.putJson("/api/push/preferences",
                "{\"categories\":[],\"quietStart\":null,\"quietEnd\":null,\"showDetails\":false,\"version\":0}");
        assertThat(stale.status()).as(stale.body()).isEqualTo(409);

        publish(ANNA, "BACKUP_FAILED");
        assertThat(dispatch.dispatchPending().sent()).as("SYSTEM is switched off").isZero();
        publish(ANNA, "TASK_COMPLETED");
        dispatch.dispatchPending();
        assertThat(sender.sent).singleElement().satisfies(sent -> assertThat(sent.message().body())
                .isEqualTo("Deine Aufgabe „Steuererklärung“ wurde erledigt."));

        assertThat(anna.delete("/api/push/devices/" + device).status()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM push_deliveries").query(Long.class).single()).isZero();
    }
}
