package dev.aegis.documents.domain.placement;

import dev.aegis.documents.domain.catalog.CatalogNames;
import dev.aegis.documents.domain.catalog.CategoryId;
import dev.aegis.documents.domain.catalog.CategoryTree;
import dev.aegis.shared.InvalidInputException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a document should be filed: either an existing folder or a path that does not exist yet, anchored at an
 * existing folder where possible. Folder names are user-authored text and are never interpreted as labels
 * (ADR-0002).
 *
 * <p>The {@link #key()} identifies the proposal independently of spelling: it is what a remembered rejection is
 * stored under and what groups identical pending proposals of several documents, so that one acceptance can serve
 * all of them.
 */
public record PlacementTarget(CategoryId folder, CategoryId anchor, List<String> newPath) {
    /** How many folders one accepted proposal may create at once. */
    public static final int MAX_NEW_FOLDERS = 3;

    public PlacementTarget {
        newPath = newPath == null ? List.of() : List.copyOf(newPath);
        if (folder == null && newPath.isEmpty()) {
            throw new InvalidInputException("placement.target.missing", "A placement needs a folder or a path");
        }
        if (folder != null && !newPath.isEmpty()) {
            throw new InvalidInputException("placement.target.ambiguous",
                    "A placement names either a folder or a new path, not both");
        }
        if (newPath.size() > MAX_NEW_FOLDERS) {
            throw new InvalidInputException("placement.path.tooDeep",
                    "A proposal may create at most " + MAX_NEW_FOLDERS + " folders",
                    Map.of("max", String.valueOf(MAX_NEW_FOLDERS)));
        }
        List<String> names = new ArrayList<>();
        for (String segment : newPath) {
            names.add(CatalogNames.requireName(segment, "category"));
        }
        for (int i = 1; i < names.size(); i++) {
            if (CatalogNames.matchKey(names.get(i)).equals(CatalogNames.matchKey(names.get(i - 1)))) {
                throw new InvalidInputException("placement.path.repeated",
                        "A path cannot repeat the same folder name");
            }
        }
        newPath = List.copyOf(names);
    }

    public static PlacementTarget existing(CategoryId folder) {
        return new PlacementTarget(Objects.requireNonNull(folder, "folder"), null, List.of());
    }

    public static PlacementTarget path(CategoryId anchor, List<String> segments) {
        return new PlacementTarget(null, anchor, segments);
    }

    public boolean isExisting() {
        return folder != null;
    }

    public Optional<CategoryId> folderId() {
        return Optional.ofNullable(folder);
    }

    public Optional<CategoryId> anchorId() {
        return Optional.ofNullable(anchor);
    }

    /**
     * Stable identity of the proposal: the folder id for an existing folder, otherwise the anchor and the
     * normalized names of the new path. Two proposals that would end in the same folder share one key.
     */
    public String key() {
        if (folder != null) {
            return "folder:" + folder.value();
        }
        return "path:" + (anchor == null ? "root" : anchor.value()) + ":"
                + String.join("/", newPath.stream().map(CatalogNames::matchKey).toList());
    }

    /** The readable path of this target, using the names of existing folders where the tree knows them. */
    public List<String> readablePath(CategoryTree tree) {
        List<String> names = new ArrayList<>();
        if (folder != null) {
            return tree.find(folder).map(found -> tree.pathNames(folder)).orElse(List.of());
        }
        if (anchor != null) {
            if (tree.find(anchor).isEmpty()) {
                return List.of();
            }
            names.addAll(tree.pathNames(anchor));
        }
        names.addAll(newPath);
        return List.copyOf(names);
    }
}
