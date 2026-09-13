package dev.aegis.documents.application;

import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Subject;
import dev.aegis.documents.domain.SubjectKind;
import dev.aegis.shared.InvalidInputException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Boundary mapping between subject views and domain subjects. */
final class Subjects {
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

    static List<SubjectView> toViews(Set<Subject> subjects) {
        return subjects.stream().map(s -> new SubjectView(s.kind().name(), s.member())).toList();
    }
}
