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
 * Base for integration tests against a real PostgreSQL (Testcontainers) with the full Spring context, two
 * provisioned members and temporary ingress/export directories. Paperless is replaced by an in-process fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestArchiveConfiguration.class)
public abstract class PostgresIT {
    public static final String ANNA = "anna";
    public static final String BEN = "ben";
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
        registry.add("db-scheduler.polling-interval", () -> "500ms");
        registry.add("aegis.archival.max-attempts", () -> "3");
        // Automatic analysis of uploads is switched on only by the tests about it (AnalysisSettings#setAutomatic).
        registry.add("aegis.documents.analysis.automatic", () -> "false");
        // AI: the real Anthropic adapter stays unconfigured (no key file); workloads use the controlled model.
        registry.add("aegis.ai.providers.anthropic.api-key-file", () -> DATA.resolve("no-anthropic-key").toString());
        registry.add("aegis.ai.daily-cost-limit", () -> "100.00");
        registry.add("aegis.ai.prices[" + ControlledModelGateway.MODEL + "].input", () -> "1.00");
        registry.add("aegis.ai.prices[" + ControlledModelGateway.MODEL + "].output", () -> "5.00");
        for (String workload : new String[] {"classification", "review", "chat"}) {
            registry.add("aegis.ai.workloads." + workload + ".provider", () -> ControlledModelGateway.PROVIDER);
            registry.add("aegis.ai.workloads." + workload + ".model", () -> ControlledModelGateway.MODEL);
        }
    }

    static Path membersFile() {
        Path file = DATA.resolve("members.json");
        if (!Files.exists(file)) {
            try {
                Files.writeString(file, """
                        {"members": [
                          {"username": "anna", "displayName": "Anna", "initialPassword": "%s", "locale": "DE"},
                          {"username": "ben", "displayName": "Ben", "initialPassword": "%s", "locale": "EN"}
                        ]}
                        """.formatted(PASSWORD, PASSWORD), StandardCharsets.UTF_8);
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
