package dev.aegis.documents.domain.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aegis.documents.domain.catalog.Category;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacementTargetTest {
    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
    private static final MemberId LISA = MemberId.random();

    @Test
    void theKeyIgnoresSpellingSoIdenticalProposalsGroupTogether() {
        CategoryId anchor = CategoryId.random();

        PlacementTarget one = PlacementTarget.path(anchor, List.of("Versicherungen", "Hausrat"));
        PlacementTarget same = PlacementTarget.path(anchor, List.of(" versicherungen ", "HAUSRAT"));
        PlacementTarget other = PlacementTarget.path(null, List.of("Versicherungen", "Hausrat"));

        assertThat(one.key()).isEqualTo(same.key());
        assertThat(one.key()).isNotEqualTo(other.key());
        assertThat(one.newPath()).containsExactly("Versicherungen", "Hausrat");
    }

    @Test
    void anExistingFolderAndAPathAreDifferentKindsOfTarget() {
        CategoryId folder = CategoryId.random();

        assertThat(PlacementTarget.existing(folder).key()).isEqualTo("folder:" + folder.value());
        assertThat(PlacementTarget.existing(folder).isExisting()).isTrue();
        assertThatThrownBy(() -> new PlacementTarget(folder, null, List.of("Haus")))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("either a folder or a new path");
        assertThatThrownBy(() -> new PlacementTarget(null, null, List.of()))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void aProposalStaysSmallAndUsesValidFolderNames() {
        assertThatThrownBy(() -> PlacementTarget.path(null, List.of("A", "B", "C", "D")))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(failure -> assertThat(((InvalidInputException) failure).code())
                        .isEqualTo("placement.path.tooDeep"));
        assertThatThrownBy(() -> PlacementTarget.path(null, List.of("Haus", " haus ")))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(failure -> assertThat(((InvalidInputException) failure).code())
                        .isEqualTo("placement.path.repeated"));
        assertThatThrownBy(() -> PlacementTarget.path(null, List.of("  ")))
                .isInstanceOf(InvalidInputException.class);
    }

    @Test
    void theReadablePathCombinesExistingFoldersWithTheProposedOnes() {
        CategoryTree tree = new CategoryTree(List.of());
        Category haus = tree.create(CategoryId.random(), null, "Haus", LISA, NOW);
        Category versicherungen = tree.create(CategoryId.random(), haus.id(), "Versicherungen", LISA, NOW);

        assertThat(PlacementTarget.path(versicherungen.id(), List.of("Hausrat")).readablePath(tree))
                .containsExactly("Haus", "Versicherungen", "Hausrat");
        assertThat(PlacementTarget.existing(versicherungen.id()).readablePath(tree))
                .containsExactly("Haus", "Versicherungen");
        // A target whose folder disappeared has no readable path and is therefore not shown.
        assertThat(PlacementTarget.existing(CategoryId.random()).readablePath(tree)).isEmpty();
        assertThat(PlacementTarget.path(CategoryId.random(), List.of("Hausrat")).readablePath(tree)).isEmpty();
    }
}
