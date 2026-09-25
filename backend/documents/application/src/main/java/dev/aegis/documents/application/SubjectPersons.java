package dev.aegis.documents.application;

import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A member subject names a person of the household; the assistant account is never what a document or folder is
 * about (ADR-0017). Only newly added subjects are checked, so a record that already carries another member keeps it
 * until a member removes it.
 */
final class SubjectPersons {
    private SubjectPersons() {
    }

    static void requirePersons(Collection<Subject> wanted, Collection<Subject> existing, HouseholdPersons persons) {
        List<MemberId> added = wanted.stream().filter(subject -> !existing.contains(subject))
                .flatMap(subject -> subject.memberId().stream()).toList();
        if (added.isEmpty()) {
            return;
        }
        List<MemberId> known = persons.persons();
        for (MemberId member : added) {
            if (!known.contains(member)) {
                throw new InvalidInputException("subject.member.notPerson",
                        "Only a person of the household can be a subject", Map.of("memberId", member.toString()));
            }
        }
    }
}
