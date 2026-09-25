package dev.aegis.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aegis.bootstrap.support.ApiClient;
import dev.aegis.bootstrap.support.PostgresIT;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.operations.adapters.storage.FileBackupStateSource;
import dev.aegis.operations.application.StorageHealthService;
import dev.aegis.operations.application.StorageHealthWatchService;
import dev.aegis.operations.application.StorageLimits;
import dev.aegis.operations.application.api.JobHealthWatch.Report;
import dev.aegis.operations.application.ports.DiskUsage;
import dev.aegis.operations.application.ports.StorageIncidentStore;
import dev.aegis.operations.application.ports.StorageIncidentStore.StorageIncident;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notices about the external backup and low disk space (issue #100, ADR-0015) against PostgreSQL: the incident store
 * keeps one open incident per subject under concurrent opening, and a watch over a temporary backup state file
 * notifies both members once per incident through the real notice inbox. The live state file is never touched.
 */
class StorageHealthWatchIT extends PostgresIT {
    private static final long GB = 1_000_000_000L;

    @LocalServerPort
    int port;

    @Autowired
    StorageIncidentStore incidents;

    @Autowired
    MemberDirectory members;

    @Autowired
    PublishNotice notices;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path dir;

    @Test
    void concurrentOpeningKeepsOneOpenIncidentAndAResolvedIncidentIsNotReopened() throws Exception {
        String subject = "it-disk-" + System.nanoTime();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<StorageIncident>> opened = new ArrayList<>();
            Callable<StorageIncident> open = () -> {
                start.await();
                return incidents.openIfAbsent(new StorageIncident(UUID.randomUUID(), subject, "LOW", null, null,
                        4 * GB, 93 * GB, now));
            };
            for (int i = 0; i < 8; i++) {
                opened.add(pool.submit(open));
            }
            start.countDown();
            Set<UUID> ids = opened.stream().map(StorageHealthWatchIT::await).map(StorageIncident::id)
                    .collect(Collectors.toSet());
            pool.shutdown();

            assertThat(ids).hasSize(1);
            StorageIncident incident = incidents.findOpen(subject).orElseThrow();
            assertThat(incident).extracting(StorageIncident::condition, StorageIncident::failureCode,
                    StorageIncident::lastSuccessAt, StorageIncident::freeBytes, StorageIncident::totalBytes,
                    StorageIncident::openedAt).containsExactly("LOW", null, null, 4 * GB, 93 * GB, now);

            incidents.resolve(incident.id(), now.plusSeconds(60));
            assertThat(incidents.findOpen(subject)).isEmpty();
            StorageIncident next = incidents.openIfAbsent(new StorageIncident(UUID.randomUUID(), subject, "FAILED",
                    "check.failed", now, null, null, now.plusSeconds(120)));
            assertThat(next.id()).isNotEqualTo(incident.id());
            assertThat(next.freeBytes()).isNull();
            assertThat(next.failureCode()).isEqualTo("check.failed");
        } finally {
            jdbc.sql("DELETE FROM storage_health_incidents WHERE subject = ?").param(subject).update();
        }
    }

    @Test
    void aFailedBackupAndALowDiskNotifyBothMembersOnceUntilTheyRecover() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant lastSuccess = now.minus(Duration.ofHours(30));
        Path state = dir.resolve("state.json");
        Files.writeString(state, """
                {"schemaVersion":2,"destinationVerified":false,"lastSuccessAt":"%s","lastAttemptAt":"%s",
                 "runningSince":null,"failureCode":"destination.notMounted","error":null}
                """.formatted(lastSuccess, now));
        DiskUsage[] data = {new DiskUsage(93 * GB, 3 * GB, now)};
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        StorageHealthService storage = new StorageHealthService(new FileBackupStateSource(state.toString(), mapper),
                () -> data[0], new StorageLimits(0.10, 5 * GB), clock);
        StorageHealthWatchService watch = new StorageHealthWatchService(storage, incidents, members, notices, clock);
        removeBackupAndDataDiskIncidents();
        try {
            assertThat(watch.check()).isEqualTo(new Report(2, 0, List.of()));
            assertThat(watch.check()).isEqualTo(new Report(0, 0, List.of()));

            for (String member : List.of(ANNA, BEN)) {
                ApiClient client = login(member);
                JsonNode backup = single(client, "BACKUP_FAILED");
                assertThat(backup.path("linkType").asText()).isEqualTo("STATUS");
                assertThat(backup.path("linkId").asText()).isEqualTo("backup");
                assertThat(backup.path("params").path("state").asText()).isEqualTo("FAILED");
                assertThat(backup.path("params").path("reason").asText()).isEqualTo("destination.notMounted");
                assertThat(Instant.parse(backup.path("params").path("lastSuccessAt").asText())).isEqualTo(lastSuccess);

                JsonNode disk = single(client, "DISK_SPACE_LOW");
                assertThat(disk.path("linkId").asText()).isEqualTo("disk:data");
                assertThat(disk.path("params").propertyNames()).containsExactlyInAnyOrder("disk", "freeBytes",
                        "totalBytes");
                assertThat(disk.path("params").path("freeBytes").asText()).isEqualTo(String.valueOf(3 * GB));
            }

            Files.writeString(state, """
                    {"schemaVersion":2,"destinationVerified":true,"lastSuccessAt":"%s","lastAttemptAt":"%s",
                     "runningSince":null,"failureCode":null,"error":null}
                    """.formatted(now, now));
            data[0] = new DiskUsage(93 * GB, 40 * GB, now);
            assertThat(watch.check()).isEqualTo(new Report(0, 2, List.of()));
            assertThat(incidents.findOpen("backup")).isEmpty();
            assertThat(incidents.findOpen("disk:data")).isEmpty();
            // Two incidents, each noticed by every active member including the assistant account (ADR-0017).
            assertThat(jdbc.sql("SELECT count(*) FROM notices WHERE kind IN ('BACKUP_FAILED', 'DISK_SPACE_LOW')")
                    .query(Long.class).single()).isEqualTo(6L);
        } finally {
            removeBackupAndDataDiskIncidents();
        }
    }

    /** The subjects are fixed names, so this test starts and ends without incidents or notices about them. */
    private void removeBackupAndDataDiskIncidents() {
        jdbc.sql("DELETE FROM storage_health_incidents WHERE subject IN ('backup', 'disk:data')").update();
        jdbc.sql("DELETE FROM notices WHERE kind IN ('BACKUP_FAILED', 'DISK_SPACE_LOW')").update();
    }

    @Test
    void theStatusPageReportsTheBackupAndTheDataDiskToMembers() {
        ApiClient anonymous = new ApiClient(port);
        assertThat(anonymous.get("/api/status").status()).isEqualTo(401);

        ApiClient anna = login(ANNA);
        JsonNode storage = anna.json(anna.get("/api/status")).path("storage");
        assertThat(storage.path("backup").path("state").asText()).isEqualTo("NOT_CONFIGURED");
        JsonNode data = storage.path("disks").get(0);
        assertThat(data.path("name").asText()).isEqualTo("data");
        assertThat(data.path("state").asText()).isEqualTo("OK");
        assertThat(data.path("totalBytes").asLong()).isPositive();
        assertThat(data.path("freeBytes").asLong()).isBetween(0L, data.path("totalBytes").asLong());
        // PostgresIT sets near-zero limits (1 MB or 0.01 %), so the test disk is never low.
        assertThat(data.path("lowBelowBytes").asLong()).isGreaterThanOrEqualTo(1024L * 1024);
    }

    private static JsonNode single(ApiClient client, String kind) {
        List<JsonNode> own = StreamSupport.stream(client.json(client.get("/api/notices")).spliterator(), false)
                .filter(notice -> kind.equals(notice.path("kind").asText())).toList();
        assertThat(own).hasSize(1);
        return own.get(0);
    }

    private static StorageIncident await(Future<StorageIncident> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ApiClient login(String username) {
        ApiClient client = new ApiClient(port);
        assertThat(client.login(username, PASSWORD)).isTrue();
        return client;
    }
}
