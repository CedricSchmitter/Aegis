package dev.aegis.bootstrap.support;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties the household tables for restore tests; members stay, as on a fresh instance with the same usernames.
 * TRUNCATE locks the tables one after another while background jobs of the running application may read them in
 * another order, so PostgreSQL can end the TRUNCATE as a deadlock victim. Only that statement is rolled back, so it is
 * repeated a few times.
 */
public final class HouseholdDataWipe {
    private static final String TRUNCATE = "TRUNCATE TABLE scheduled_tasks, notices, tasks, document_labels, "
            + "document_placement_suggestions, document_placement_rejections, document_classifications, "
            + "document_analyses, document_text_checks, document_subjects, document_bulk_analysis_items, "
            + "document_derivations, document_page_edits, "
            + "document_bulk_analyses, documents";
    private static final int ATTEMPTS = 5;

    private HouseholdDataWipe() {
    }

    public static void truncate(JdbcTemplate jdbc) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.execute(TRUNCATE);
                break;
            } catch (PessimisticLockingFailureException deadlock) {
                if (attempt == ATTEMPTS) {
                    throw deadlock;
                }
                Thread.sleep(200L * attempt);
            }
        }
        jdbc.execute("DELETE FROM category_label_effects");
        jdbc.execute("DELETE FROM category_subject_effects");
        jdbc.execute("DELETE FROM category_implied_labels");
        jdbc.execute("DELETE FROM category_implied_subjects");
        jdbc.execute("DELETE FROM categories");
    }
}
