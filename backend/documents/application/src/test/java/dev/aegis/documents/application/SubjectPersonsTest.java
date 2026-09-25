package dev.aegis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.application.api.CategoryView;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.Trash;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A member subject names a person; the assistant account is never what a document or folder is about (ADR-0017). */
class SubjectPersonsTest {
    private CatalogFixture f;
    private DocumentCommandService commands;
    private final MemberId assistant = MemberId.random();

    @BeforeEach
    void setUp() {
        f = new CatalogFixture();
        f.access.members.add(assistant);
        commands = new DocumentCommandService(f.documents, f.scheduler, f.presenter, id -> { }, f.persons, f.access,
                Trash.RecoveryWindow.DEFAULT, f.clock);
    }

    @Test
    void aDocumentConcerningBothPersonsNamesEachOfThem() {
        Document document = f.document("Mietvertrag");

        var changed = commands.changeSubjects(f.actor, document.id(), List.of(new SubjectView("MEMBER", f.lisa),
                new SubjectView("MEMBER", f.cedric), new SubjectView("MEMBER", f.lisa)), document.version());

        assertThat(changed.subjects()).containsExactly(new SubjectView("MEMBER", f.lisa),
                new SubjectView("MEMBER", f.cedric));
    }

    @Test
    void theAssistantCannotBecomeADocumentSubject() {
        Document document = f.document("Rechnung");

        assertThatThrownBy(() -> commands.changeSubjects(f.actor, document.id(),
                List.of(new SubjectView("MEMBER", assistant)), document.version()))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "subject.member.notPerson");
        assertThatThrownBy(() -> commands.changeSubjects(f.actor, document.id(),
                List.of(new SubjectView("HOUSEHOLD", null)), document.version()))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "subject.kind.invalid");
        assertThat(f.documents.findById(document.id()).orElseThrow().subjects()).isEmpty();
    }

    @Test
    void anExistingNonPersonSubjectStaysUntilAMemberRemovesIt() {
        Document document = f.document("Altbestand");
        document.changeSubjects(Set.of(Subject.member(assistant)));
        f.documents.save(document);
        long version = f.documents.findById(document.id()).orElseThrow().version();

        var kept = commands.changeSubjects(f.actor, document.id(), List.of(new SubjectView("MEMBER", assistant),
                new SubjectView("MEMBER", f.lisa)), version);
        var removed = commands.changeSubjects(f.actor, document.id(), List.of(new SubjectView("MEMBER", f.lisa)),
                kept.version());

        assertThat(kept.subjects()).containsExactly(new SubjectView("MEMBER", assistant),
                new SubjectView("MEMBER", f.lisa));
        assertThat(removed.subjects()).containsExactly(new SubjectView("MEMBER", f.lisa));
    }

    @Test
    void aFolderImpliesOnlyPersons() {
        CategoryView folder = f.folder("Lisa", null);

        assertThatThrownBy(() -> f.categoryService.changeImplications(f.actor, folder.id(), List.of(),
                List.of(new SubjectView("MEMBER", assistant)), folder.version()))
                .isInstanceOf(InvalidInputException.class)
                .hasFieldOrPropertyWithValue("code", "subject.member.notPerson");
        CategoryView mapped = f.categoryService.changeImplications(f.actor, folder.id(), List.of(),
                List.of(new SubjectView("MEMBER", f.lisa)), folder.version());

        assertThat(mapped.impliedSubjects()).containsExactly(new SubjectView("MEMBER", f.lisa));
    }
}
