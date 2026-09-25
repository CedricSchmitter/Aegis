package dev.aegis.documents.application.api;

/**
 * Replaces the retired subject value "both" ({@code HOUSEHOLD}) by one subject per person of the household, on
 * documents, folders and pending analysis suggestions (ADR-0017). Runs at every startup and changes nothing once no
 * such value is left.
 */
public interface MigrateHouseholdSubjects {

    /** What one run changed; {@code persons} is the number of persons each stored value was replaced with. */
    record Result(boolean needed, int persons, int documents, int folders, int analyses) {
        public static final Result NOTHING_TO_MIGRATE = new Result(false, 0, 0, 0, 0);
    }

    Result migrateHouseholdSubjects();
}
