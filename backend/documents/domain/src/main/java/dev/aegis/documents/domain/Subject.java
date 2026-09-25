package dev.aegis.documents.domain;

import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.util.Optional;

/**
 * Subject association of a document: a person of the household or the house. Distinct from the uploader and from any
 * task owner. A document may carry several subjects, for example one per person it concerns.
 */
public record Subject(SubjectKind kind, MemberId member) {
    public Subject {
        if (kind == null) {
            throw new InvalidInputException("subject.kind.missing", "Subject kind is required");
        }
        if (kind == SubjectKind.MEMBER && member == null) {
            throw new InvalidInputException("subject.member.missing", "Member subject needs a member id");
        }
        if (kind != SubjectKind.MEMBER && member != null) {
            throw new InvalidInputException("subject.member.unexpected", "Only member subjects carry a member id");
        }
    }

    public static Subject member(MemberId member) {
        return new Subject(SubjectKind.MEMBER, member);
    }

    public static Subject house() {
        return new Subject(SubjectKind.HOUSE, null);
    }

    public Optional<MemberId> memberId() {
        return Optional.ofNullable(member);
    }
}
