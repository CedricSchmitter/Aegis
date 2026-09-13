package dev.aegis.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Composition root. Wires contexts, security, transactions and infrastructure; contains no business rules. */
@SpringBootApplication(scanBasePackages = "dev.aegis")
@EntityScan("dev.aegis")
@EnableJpaRepositories("dev.aegis")
public class AegisApplication {
    public static void main(String[] args) {
        SpringApplication.run(AegisApplication.class, args);
    }
}
