package dev.aegis.documents.application.ports;

import dev.aegis.shared.MemberId;
import java.util.List;

/**
 * The persons of the household: the members a document can be about. Implemented with the identity context's public
 * member directory; the household's assistant account is never one of them (ADR-0017).
 */
public interface HouseholdPersons {
    /** Active persons of the household, in a stable order. */
    List<MemberId> persons();
}
