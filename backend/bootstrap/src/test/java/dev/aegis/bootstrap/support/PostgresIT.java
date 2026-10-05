package dev.aegis.bootstrap.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests against a real PostgreSQL (Testcontainers) with the full Spring context, two provisioned
 * persons, the household's assistant account and temporary ingress/export directories. Paperless is replaced by an
 * in-process fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestArchiveConfiguration.class)
public abstract class PostgresIT {
    public static final String ANNA = "anna";
    public static final String BEN = "ben";
    /** Designated as the household's assistant at startup and then shown as "Aegis" (ADR-0017). */
    public static final String HELPER = "helper";
    public static final String PASSWORD = "synthetic-test-password";
    public static final Path FIXTURE = Path.of("..", "..", "fixtures", "synthetic-contract.pdf");

    /**
     * Started once per JVM (not per test class) because the Spring test context is cached across the IT classes
     * and must keep talking to the same database. Testcontainers' Ryuk removes the container at JVM exit.
     */
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11");

    static {
        postgres.start();
    }

    protected static final Path DATA = createDataDir();

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("aegis-it-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void aegisProperties(DynamicPropertyRegistry registry) {
        registry.add("aegis.ingress.dir", () -> DATA.resolve("ingress").toString());
        registry.add("aegis.exports.dir", () -> DATA.resolve("exports").toString());
        registry.add("aegis.members.file", () -> membersFile().toString());
        registry.add("aegis.members.assistants", () -> HELPER);
        // Short job timing keeps the suite fast; the paths are the same as in production, only the waits shrink. An
        // archival needs a submission and two consumption checks, so production's 5s check interval alone cost 10s per
        // upload. The retry base leaves ApiFlowIT's outage test seconds, not milliseconds, between failed attempts.
        registry.add("db-scheduler.polling-interval", () -> "100ms");
        registry.add("aegis.archival.max-attempts", () -> "3");
        registry.add("aegis.archival.poll-interval", () -> "100ms");
        registry.add("aegis.archival.retry-base", () -> "2s");
        // The review reconciliation job runs once at startup and then only when a test triggers it, so its notices
        // never appear in the middle of another test's notice counts (ReviewReconciliationIT).
        registry.add("aegis.review.reconciliation-interval", () -> "12h");
        // The job health watch likewise runs only at startup, inside its start grace, so it never adds notices while
        // other tests truncate or stage scheduler rows (JobHealthWatchIT drives its own watch).
        registry.add("aegis.operations.job-health-watch-interval", () -> "12h");
        // Pushes are dispatched only when PushIT triggers it, so other tests' notices are never sent mid-test.
        registry.add("aegis.push.dispatch-interval", () -> "12h");
        registry.add("aegis.documents.passages.interval", () -> "12h");
        // The same startup run also watches disk space. The temporary data directory can sit on a small tmpfs (3.9 GB
        // on the VM, below the 5 GB limit), so near-zero limits keep the test data disk from counting as low and
        // opening an incident behind StorageHealthWatchIT's back; that test drives its own watch and limits.
        registry.add("aegis.storage.low-free-space", () -> "1MB");
        registry.add("aegis.storage.low-free-fraction", () -> "0.0001");
        // Automatic analysis of uploads is switched on only by the tests about it (AnalysisSettings#setAutomatic).
        registry.add("aegis.documents.analysis.automatic", () -> "false");
        // Bulk classification (BulkAnalysisIT): two runs in flight and short steps instead of production's 10s.
        registry.add("aegis.documents.analysis.bulk.batch-size", () -> "2");
        registry.add("aegis.documents.analysis.bulk.poll-interval", () -> "200ms");
        registry.add("aegis.documents.analysis.bulk.max-wait", () -> "2s");
        // AI: the real Anthropic adapter stays unconfigured (no key file); workloads use the controlled model.
        registry.add("aegis.ai.providers.anthropic.api-key-file", () -> DATA.resolve("no-anthropic-key").toString());
        registry.add("aegis.ai.providers.claude-code.runner-socket", () -> DATA.resolve("no-runner.sock").toString());
        registry.add("aegis.ai.daily-cost-limit", () -> "100.00");
        // One run at a time and production's slot wait (15s): a freed slot is handed to the oldest waiting run, so
        // queued runs of a burst start right after each other (AiSlotHandOverIT, DocumentClassificationIT).
        registry.add("aegis.ai.prices[" + ControlledModelGateway.MODEL + "].input", () -> "1.00");
        registry.add("aegis.ai.prices[" + ControlledModelGateway.MODEL + "].output", () -> "5.00");
        registry.add("aegis.ai.models.sonnet.model", () -> ControlledModelGateway.MODEL);
        for (String workload : new String[] {"classification", "review", "chat"}) {
            registry.add("aegis.ai.workloads." + workload + ".providers", () -> ControlledModelGateway.PROVIDER);
        }
    }

    static Path membersFile() {
        Path file = DATA.resolve("members.json");
        if (!Files.exists(file)) {
            try {
                Files.writeString(file, """
                        {"members": [
                          {"username": "anna", "displayName": "Anna", "initialPassword": "%s", "locale": "DE"},
                          {"username": "ben", "displayName": "Ben", "initialPassword": "%s", "locale": "EN"},
                          {"username": "helper", "displayName": "Helper Dev", "initialPassword": "%s", "locale": "DE"}
                        ]}
                        """.formatted(PASSWORD, PASSWORD, PASSWORD), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return file;
    }

    @BeforeAll
    static void fixtureExists() {
        if (!Files.isRegularFile(FIXTURE)) {
            throw new IllegalStateException("fixture missing: " + FIXTURE.toAbsolutePath());
        }
    }

    protected static byte[] fixtureBytes() throws IOException {
        return Files.readAllBytes(FIXTURE);
    }
}
