package dev.aegis.documents.application;

import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Boundary mapping between subject views, subject keys and domain subjects. */
final class Subjects {
    private static final String MEMBER_PREFIX = "MEMBER:";

    private Subjects() {
    }

    static Set<Subject> fromViews(List<SubjectView> views) {
        Set<Subject> subjects = new LinkedHashSet<>();
        if (views == null) {
            return subjects;
        }
        for (SubjectView view : views) {
            SubjectKind kind;
            try {
                kind = SubjectKind.valueOf(view.kind());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new InvalidInputException("subject.kind.invalid", "Unknown subject kind " + view.kind());
            }
            subjects.add(new Subject(kind, view.memberId()));
        }
        return subjects;
    }

    static List<SubjectView> toViews(Collection<Subject> subjects) {
        return subjects.stream().map(s -> new SubjectView(s.kind().name(), s.member())).toList();
    }

    /** Stable key used in search filters and counts: HOUSE, HOUSEHOLD or MEMBER:{uuid}. */
    static String key(Subject subject) {
        return subject.memberId().map(member -> MEMBER_PREFIX + member).orElse(subject.kind().name());
    }

    static Subject fromKey(String key) {
        if (key != null && key.startsWith(MEMBER_PREFIX)) {
            return Subject.member(MemberId.of(key.substring(MEMBER_PREFIX.length())));
        }
        return fromViews(List.of(new SubjectView(key, null))).iterator().next();
    }
}
