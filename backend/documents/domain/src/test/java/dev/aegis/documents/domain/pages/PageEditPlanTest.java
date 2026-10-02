package dev.aegis.documents.domain.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PageEditPlanTest {
    private static final PageEditPlan.Limits LIMITS = new PageEditPlan.Limits(3, 4, 2, 2);
    private final DocumentId scan = DocumentId.random();
    private final DocumentId contract = DocumentId.random();
    private final DocumentId annex = DocumentId.random();

    private static PageEditOutput output(String title, PageRef... pages) {
        return new PageEditOutput(title, false, null, Set.of(Subject.house()), List.of(pages));
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InvalidInputException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void splittingAndMergingAreBothPlansOfOutputsMadeOfPages() {
        PageEditPlan split = new PageEditPlan(List.of(
                output("Letter A", PageRef.ofDocument(scan, 0, 0), PageRef.ofDocument(scan, 1, 0)),
                output("Letter B", PageRef.ofDocument(scan, 2, 180))));
        PageEditPlan merge = new PageEditPlan(List.of(output("Contract with annex",
                PageRef.ofDocument(contract, 0, 0), PageRef.ofDocument(annex, 0, 0), PageRef.ofScan(0, 90))));

        assertThat(split.sourceDocuments()).containsExactly(scan);
        assertThat(split.usesScan()).isFalse();
        assertThat(merge.sourceDocuments()).containsExactly(contract, annex);
        assertThat(merge.usesScan()).isTrue();
        assertThatCode(() -> split.validate(LIMITS, Map.of(scan, 3), 0)).doesNotThrowAnyException();
        assertThatCode(() -> merge.validate(LIMITS, Map.of(contract, 1, annex, 1), 1)).doesNotThrowAnyException();
    }

    @Test
    void pagesAndRotationsMustBeValid() {
        assertCode(() -> PageRef.ofDocument(scan, -1, 0), "pageEdit.page.invalid");
        assertCode(() -> PageRef.ofScan(0, 45), "pageEdit.rotation.invalid");
        assertCode(() -> PageRef.ofDocument(null, 0, 0), "pageEdit.source.missing");
        PageEditPlan beyond = new PageEditPlan(List.of(output("A", PageRef.ofDocument(scan, 3, 0))));
        assertCode(() -> beyond.validate(LIMITS, Map.of(scan, 3), 0), "pageEdit.page.invalid");
        PageEditPlan scanned = new PageEditPlan(List.of(output("A", PageRef.ofScan(1, 0))));
        assertCode(() -> scanned.validate(LIMITS, Map.of(), 0), "pageEdit.scan.missing");
        assertCode(() -> scanned.validate(LIMITS, Map.of(), 1), "pageEdit.page.invalid");
    }

    @Test
    void emptyPlansOutputsAndTitlesAreRefused() {
        assertCode(() -> new PageEditPlan(List.of()), "pageEdit.plan.empty");
        assertCode(() -> output("A"), "pageEdit.output.empty");
        assertCode(() -> output(" ", PageRef.ofScan(0, 0)), "document.title.missing");
        assertCode(() -> output("x".repeat(201), PageRef.ofScan(0, 0)), "document.title.tooLong");
        assertThat(output("  Letter  ", PageRef.ofScan(0, 0)).title()).isEqualTo("Letter");
    }

    @Test
    void limitsBoundOutputsPagesSourcesAndNewPages() {
        PageRef page = PageRef.ofDocument(scan, 0, 0);
        PageEditPlan tooManyOutputs = new PageEditPlan(IntStream.range(0, 4).mapToObj(i -> output("A", page))
                .toList());
        assertCode(() -> tooManyOutputs.validate(LIMITS, Map.of(scan, 1), 0), "pageEdit.limit.outputs");

        PageEditPlan tooManyPages = new PageEditPlan(List.of(output("A", page, page, page, page, page)));
        assertCode(() -> tooManyPages.validate(LIMITS, Map.of(scan, 1), 0), "pageEdit.limit.pages");

        PageEditPlan tooManySources = new PageEditPlan(List.of(output("A", page,
                PageRef.ofDocument(contract, 0, 0), PageRef.ofDocument(annex, 0, 0))));
        assertCode(() -> tooManySources.validate(LIMITS, Map.of(scan, 1, contract, 1, annex, 1), 0),
                "pageEdit.limit.sources");

        PageEditPlan scanned = new PageEditPlan(List.of(output("A", PageRef.ofScan(0, 0))));
        assertCode(() -> scanned.validate(LIMITS, Map.of(), 3), "pageEdit.limit.newPages");
    }

    @Test
    void equalPlansAreRecognizedRegardlessOfSubjectOrder() {
        Subject house = Subject.house();
        Subject lisa = Subject.member(dev.aegis.shared.MemberId.random());
        PageEditPlan first = new PageEditPlan(List.of(new PageEditOutput("A", true, null,
                new java.util.LinkedHashSet<>(List.of(house, lisa)), List.of(PageRef.ofScan(0, 0)))));
        PageEditPlan second = new PageEditPlan(List.of(new PageEditOutput("A", true, null,
                new java.util.LinkedHashSet<>(List.of(lisa, house)), List.of(PageRef.ofScan(0, 0)))));
        PageEditPlan rotated = new PageEditPlan(List.of(new PageEditOutput("A", true, null,
                Set.of(house, lisa), List.of(PageRef.ofScan(0, 90)))));
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(rotated);
    }
}
