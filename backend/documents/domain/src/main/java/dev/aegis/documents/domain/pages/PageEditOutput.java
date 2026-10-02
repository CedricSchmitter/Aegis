package dev.aegis.documents.domain.pages;

import dev.aegis.documents.domain.Document;
import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.InvalidInputException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One new document of an edit plan: its pages in order and the title, folder and subjects it is created with.
 * {@code titleChosen} tells a title the member typed from the one the editor prefilled; only a prefilled title may
 * later be replaced by automatic classification (ADR-0004). {@code categoryId} is null for Unsorted.
 */
public record PageEditOutput(String title, boolean titleChosen, UUID categoryId, Set<Subject> subjects,
        List<PageRef> pages) {

    public PageEditOutput {
        if (title == null || title.isBlank()) {
            throw new InvalidInputException("document.title.missing", "Title is required");
        }
        title = title.trim();
        if (title.length() > Document.MAX_TITLE_LENGTH) {
            throw new InvalidInputException("document.title.tooLong", "Title exceeds " + Document.MAX_TITLE_LENGTH);
        }
        subjects = Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(subjects, "subjects")));
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty()) {
            throw new InvalidInputException("pageEdit.output.empty", "Every new document needs at least one page");
        }
    }
}
