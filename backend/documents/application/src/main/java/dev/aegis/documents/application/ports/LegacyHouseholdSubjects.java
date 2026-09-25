package dev.aegis.documents.application.ports;

import dev.aegis.shared.MemberId;
import java.util.List;

/**
 * Stored values of the retired subject kind {@code HOUSEHOLD} ("both"), which the domain no longer represents
 * (ADR-0017). Implemented by the persistence adapter that owns the documents context's tables.
 */
public interface LegacyHouseholdSubjects {

    /** Rows changed per record type. */
    record Replaced(int documents, int folders, int analyses) {
    }

    boolean present();

    /**
     * Replaces every stored {@code HOUSEHOLD} value by one member value per person in one transaction. A value the
     * record already carries for that person wins; a pending suggestion is not repeated for a person the document
     * is already about. Record versions stay as they are, so open review cards and their notices are not renewed.
     */
    Replaced replaceWith(List<MemberId> persons);
}
