package dev.aegis.documents.domain.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.Subject;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import dev.aegis.shared.NotFoundException;
import dev.aegis.shared.StaleVersionException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CategoryTreeTest {
    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final MemberId LISA = MemberId.random();
    private static final LabelId INVOICE = LabelId.random();
    private static final LabelId CONTRACT = LabelId.random();
    private static final LabelId INTERNET = LabelId.random();
    private static final LabelId WATER = LabelId.random();
    private static final Map<LabelId, Facet> FACETS = Map.of(INVOICE, Facet.DOCUMENT_TYPE, CONTRACT,
            Facet.DOCUMENT_TYPE, INTERNET, Facet.TOPIC, WATER, Facet.TOPIC);
    private static final Function<LabelId, Facet> FACET_OF = FACETS::get;

    private final CategoryTree tree = new CategoryTree(List.of());
    private final CategoryId house = tree.create(CategoryId.random(), null, "Haus", LISA, NOW).id();
    private final CategoryId invoices = tree.create(CategoryId.random(), house, "Rechnungen", LISA, NOW).id();
    private final CategoryId internet = tree.create(CategoryId.random(), invoices, "Internet", LISA, NOW).id();

    @Test
    void pathsAndSubtreesFollowTheHierarchy() {
        CategoryId water = tree.create(CategoryId.random(), invoices, "Wasser", LISA, NOW).id();

        assertThat(tree.path(internet)).extracting(Category::name).containsExactly("Haus", "Rechnungen", "Internet");
        assertThat(tree.subtree(invoices)).containsExactlyInAnyOrder(invoices, internet, water);
        assertThat(tree.childrenOf(invoices)).extracting(Category::name).containsExactly("Internet", "Wasser");
        assertThat(tree.childrenOf(null)).extracting(Category::id).containsExactly(house);
    }

    @Test
    void siblingNamesAreUniqueIgnoringCaseAndSpacingButMayRepeatElsewhere() {
        assertThatThrownBy(() -> tree.create(CategoryId.random(), invoices, "  internet ", LISA, NOW))
                .isInstanceOfSatisfying(InvalidInputException.class, e -> {
                    assertThat(e.code()).isEqualTo("category.name.duplicate");
                    assertThat(e.details()).containsEntry("existingId", internet.toString());
                });

        Category otherInternet = tree.create(CategoryId.random(), null, "Internet", LISA, NOW);

        assertThat(otherInternet.parentId()).isEmpty();
        assertThatThrownBy(() -> tree.create(CategoryId.random(), null, "   ", LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "category.name.missing");
        assertThatThrownBy(() -> tree.create(CategoryId.random(), null, "x".repeat(81), LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "category.name.tooLong");
    }

    @Test
    void movesRejectCyclesAndKeepIdentity() {
        assertThatThrownBy(() -> tree.move(house, internet, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "category.cycle");
        assertThatThrownBy(() -> tree.move(house, house, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "category.cycle");

        Category moved = tree.move(internet, house, 0, NOW);

        assertThat(moved.id()).isEqualTo(internet);
        assertThat(tree.path(internet)).extracting(Category::name).containsExactly("Haus", "Internet");
        assertThat(tree.changes().upserts()).extracting(Category::id).contains(internet);
    }

    @Test
    void moveToTopLevelAndUnknownParentsAreHandled() {
        tree.move(internet, null, 0, NOW);

        assertThat(tree.path(internet)).hasSize(1);
        assertThatThrownBy(() -> tree.move(internet, CategoryId.random(), 0, NOW))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void depthIsBoundedForCreateAndForMovingWholeSubtrees() {
        CategoryId deepest = internet;
        for (int level = 4; level <= CategoryTree.MAX_DEPTH; level++) {
            deepest = tree.create(CategoryId.random(), deepest, "Level " + level, LISA, NOW).id();
        }
        CategoryId last = deepest;
        assertThatThrownBy(() -> tree.create(CategoryId.random(), last, "Too deep", LISA, NOW))
                .hasFieldOrPropertyWithValue("code", "category.depth");

        CategoryId other = tree.create(CategoryId.random(), null, "Other", LISA, NOW).id();
        tree.create(CategoryId.random(), other, "Child", LISA, NOW);
        CategoryId parentAtSeven = tree.path(last).get(CategoryTree.MAX_DEPTH - 2).id();
        assertThatThrownBy(() -> tree.move(other, parentAtSeven, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "category.depth");
    }

    @Test
    void staleVersionsAreReportedInsteadOfOverwritten() {
        assertThatThrownBy(() -> tree.rename(internet, "DSL", 3, NOW)).isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> tree.remove(internet, 1, NOW)).isInstanceOf(StaleVersionException.class);
    }

    @Test
    void renameChecksSiblingsAndIgnoresUnchangedNames() {
        tree.create(CategoryId.random(), invoices, "Wasser", LISA, NOW);

        assertThatThrownBy(() -> tree.rename(internet, "WASSER", 0, NOW))
                .hasFieldOrPropertyWithValue("code", "category.name.duplicate");
        CategoryTree fresh = new CategoryTree(tree.all());
        fresh.rename(internet, "Internet", 0, NOW);
        assertThat(fresh.changes().upserts()).isEmpty();
        assertThat(tree.rename(internet, "Internet & Telefon", 0, NOW).name()).isEqualTo("Internet & Telefon");
    }

    @Test
    void removingAFolderMovesSubfoldersUpAndReportsTheContentsTarget() {
        CategoryTree.Removal removal = tree.remove(invoices, 0, NOW);

        assertThat(removal.contentsTarget()).contains(house);
        assertThat(removal.movedChildren()).containsExactly(internet);
        assertThat(tree.find(invoices)).isEmpty();
        assertThat(tree.path(internet)).extracting(Category::name).containsExactly("Haus", "Internet");
        assertThat(tree.changes().deletions()).containsExactly(invoices);

        CategoryTree.Removal top = tree.remove(house, 0, NOW);
        assertThat(top.contentsTarget()).isEmpty();
        assertThat(tree.path(internet)).hasSize(1);
    }

    @Test
    void removalIsRefusedWhenAMovedSubfolderWouldCollideWithASibling() {
        tree.create(CategoryId.random(), house, "Internet", LISA, NOW);

        assertThatThrownBy(() -> tree.remove(invoices, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "category.delete.nameConflict");
        assertThat(tree.find(invoices)).isPresent();
    }

    @Test
    void implicationsInheritFromAncestorsAndTheNearestTypeWins() {
        tree.changeImplications(house, Set.of(CONTRACT), Set.of(Subject.house()), FACET_OF, 0, NOW);
        tree.changeImplications(invoices, Set.of(INVOICE), Set.of(), FACET_OF, 0, NOW);
        tree.changeImplications(internet, Set.of(INTERNET), Set.of(Subject.household()), FACET_OF, 0, NOW);

        CategoryTree.Implications implications = tree.implications(internet, FACET_OF);

        assertThat(implications.labels()).containsOnly(Map.entry(INTERNET, internet), Map.entry(INVOICE, invoices));
        assertThat(implications.subjects()).containsOnly(Map.entry(Subject.house(), house),
                Map.entry(Subject.household(), internet));
        assertThat(tree.implications(house, FACET_OF).labels()).containsOnlyKeys(CONTRACT);
        assertThat(tree.implications(null, FACET_OF)).isEqualTo(CategoryTree.Implications.NONE);
        assertThat(tree.effects(FACET_OF)).filteredOn(e -> e.category().equals(internet)).singleElement()
                .satisfies(e -> {
                    assertThat(e.labels()).containsOnly(Map.entry(INTERNET, Facet.TOPIC),
                            Map.entry(INVOICE, Facet.DOCUMENT_TYPE));
                    assertThat(e.subjects()).containsExactlyInAnyOrder(Subject.house(), Subject.household());
                });
    }

    @Test
    void aFolderImpliesAtMostOneDocumentType() {
        assertThatThrownBy(() -> tree.changeImplications(invoices, Set.of(INVOICE, CONTRACT), Set.of(), FACET_OF, 0,
                NOW)).hasFieldOrPropertyWithValue("code", "category.implication.multipleTypes");
    }

    @Test
    void mergedLabelsAreReplacedInFolderMappings() {
        tree.changeImplications(internet, Set.of(WATER), Set.of(), FACET_OF, 0, NOW);
        CategoryTree reloaded = new CategoryTree(tree.all());

        reloaded.replaceImpliedLabel(WATER, INTERNET, NOW);

        assertThat(reloaded.require(internet).impliedLabels()).containsExactly(INTERNET);
        assertThat(reloaded.changes().upserts()).extracting(Category::id).containsExactly(internet);
    }

    @Test
    void persistenceOrderStoresParentsBeforeChildren() {
        assertThat(tree.changes().upserts()).extracting(Category::id).containsExactly(house, invoices, internet);
    }

    @Test
    void cyclicStoredDataIsDetected() {
        CategoryId a = CategoryId.random();
        CategoryId b = CategoryId.random();
        Category first = Category.rehydrate(a, b, "A", Set.of(), Set.of(), LISA, NOW, NOW, 0);
        Category second = Category.rehydrate(b, a, "B", Set.of(), Set.of(), LISA, NOW, NOW, 0);

        assertThatThrownBy(() -> new CategoryTree(List.of(first, second))).isInstanceOf(IllegalStateException.class);
    }
}
