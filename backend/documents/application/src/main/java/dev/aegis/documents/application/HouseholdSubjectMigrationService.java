package dev.aegis.documents.application;

import dev.aegis.documents.application.api.MigrateHouseholdSubjects;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.LegacyHouseholdSubjects;
import dev.aegis.documents.application.ports.LegacyHouseholdSubjects.Replaced;
import dev.aegis.shared.MemberId;
import java.util.List;

/**
 * Turns the retired "both" subject into the persons it meant (ADR-0017). "Both" meant the household's persons, so the
 * assistant account is never added. The persons come from the identity contract through {@link HouseholdPersons};
 * the documents context never reads identity tables. Wrapped in one transaction by the composition root.
 */
public class HouseholdSubjectMigrationService implements MigrateHouseholdSubjects {
    private final LegacyHouseholdSubjects legacy;
    private final HouseholdPersons persons;

    public HouseholdSubjectMigrationService(LegacyHouseholdSubjects legacy, HouseholdPersons persons) {
        this.legacy = legacy;
        this.persons = persons;
    }

    @Override
    public Result migrateHouseholdSubjects() {
        if (!legacy.present()) {
            return Result.NOTHING_TO_MIGRATE;
        }
        List<MemberId> people = persons.persons();
        if (people.isEmpty()) {
            // Dropping the values would lose what the documents are about; stop until the persons exist.
            throw new IllegalStateException("documents are marked as concerning both persons, but the household has "
                    + "no active person to replace that value with");
        }
        Replaced replaced = legacy.replaceWith(people);
        return new Result(true, people.size(), replaced.documents(), replaced.folders(), replaced.analyses());
    }
}
