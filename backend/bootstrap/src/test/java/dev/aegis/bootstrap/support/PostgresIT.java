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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests against a real PostgreSQL (Testcontainers) with the full Spring context, two
 * provisioned members and temporary ingress/export directories. Paperless is replaced by an in-process fake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestArchiveConfiguration.class)
public abstract class PostgresIT {
    public static final String ANNA = "anna";
    public static final String BEN = "ben";
    public static final String PASSWORD = "synthetic-test-password";
    public static final Path FIXTURE = Path.of("..", "..", "fixtures", "synthetic-contract.pdf");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11");

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
