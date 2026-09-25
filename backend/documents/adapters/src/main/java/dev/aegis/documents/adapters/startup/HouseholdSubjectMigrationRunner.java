package dev.aegis.documents.adapters.startup;

import dev.aegis.documents.application.api.MigrateHouseholdSubjects;
import dev.aegis.documents.application.api.MigrateHouseholdSubjects.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Replaces the retired "both" subject at startup (ADR-0017): after member provisioning and assistant designation, so
 * the persons are known, and before a restore. Logs counts only, never names or document content.
 */
@Component
@Order(15)
public class HouseholdSubjectMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(HouseholdSubjectMigrationRunner.class);

    private final MigrateHouseholdSubjects migration;

    public HouseholdSubjectMigrationRunner(MigrateHouseholdSubjects migration) {
        this.migration = migration;
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = migration.migrateHouseholdSubjects();
        if (result.needed()) {
            log.info("Replaced the subject 'both' by {} persons on {} documents, {} folders and {} analyses",
                    result.persons(), result.documents(), result.folders(), result.analyses());
        }
    }
}
