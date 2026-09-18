package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.CategoryCommands.CreateCategory;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryListing;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The shared folder tree: browse, create, rename, move, map labels and delete without losing documents. */
@RestController
@RequestMapping("/api/categories")
@Tag(name = "categories")
public class CategoriesController {
    private final CatalogQueries queries;
    private final CategoryCommands commands;

    public CategoriesController(CatalogQueries queries, CategoryCommands commands) {
        this.queries = queries;
        this.commands = commands;
    }

    public record CategoryListResponse(List<CategoryDto> categories, long unsortedCount, long totalDocuments) {
    }

    public record CreateCategoryRequest(@Schema(nullable = true) String id, @Schema(nullable = true) String parentId,
            @NotBlank String name) {
    }

    public record RenameCategoryRequest(@NotBlank String name, long expectedVersion) {
    }

    public record MoveCategoryRequest(@Schema(nullable = true) String parentId, long expectedVersion) {
    }

    public record ImplicationsRequest(@NotNull List<String> labelIds,
            @NotNull List<DocumentDto.SubjectDto> subjects, long expectedVersion) {
    }

    public record DeletionResponse(String deletedId, @Schema(nullable = true) String contentsMovedTo,
            int documentsMoved, int foldersMoved, int labelsKept, int subjectsKept) {
    }

    @Operation(operationId = "listCategories", summary = "Folder tree with document counts and Unsorted size")
    @GetMapping
    public CategoryListResponse list(Actor actor) {
        CategoryListing listing = queries.categories(actor);
        return new CategoryListResponse(listing.categories().stream().map(CategoryDto::from).toList(),
                listing.unsortedCount(), listing.totalDocuments());
    }

    @Operation(operationId = "createCategory", summary = "Create a folder; a client id makes retries idempotent")
    @PostMapping
    public ResponseEntity<CategoryDto> create(Actor actor, @Valid @RequestBody CreateCategoryRequest body) {
        CategoryDto created = CategoryDto.from(commands.create(actor, new CreateCategory(
                RestIds.optional(body.id()), RestIds.optional(body.parentId()), body.name())));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(operationId = "renameCategory", summary = "Rename a folder")
    @PutMapping("/{id}/name")
    public CategoryDto rename(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody RenameCategoryRequest body) {
        return CategoryDto.from(commands.rename(actor, RestIds.required(id), body.name(), body.expectedVersion()));
    }

    @Operation(operationId = "moveCategory", summary = "Move a folder; an empty parent means the top level")
    @PutMapping("/{id}/parent")
    public CategoryDto move(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody MoveCategoryRequest body) {
        return CategoryDto.from(commands.move(actor, RestIds.required(id), RestIds.optional(body.parentId()),
                body.expectedVersion()));
    }

    @Operation(operationId = "changeCategoryImplications",
            summary = "Replace the labels and subjects documents inherit from this folder")
    @PutMapping("/{id}/implications")
    public CategoryDto implications(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody ImplicationsRequest body) {
        return CategoryDto.from(commands.changeImplications(actor, RestIds.required(id),
                RestIds.all(body.labelIds()), body.subjects().stream().map(DocumentDto.SubjectDto::toView).toList(),
                body.expectedVersion()));
    }

    @Operation(operationId = "deleteCategory",
            summary = "Delete a folder; documents and subfolders move up and keep the labels they would lose")
    @DeleteMapping("/{id}")
    public DeletionResponse delete(Actor actor, @PathVariable("id") String id,
            @RequestParam("expectedVersion") long expectedVersion) {
        var result = commands.delete(actor, RestIds.required(id), expectedVersion);
        return new DeletionResponse(result.deletedId().toString(),
                result.contentsMovedTo() == null ? null : result.contentsMovedTo().toString(),
                result.documentsMoved(), result.foldersMoved(), result.labelsKept(), result.subjectsKept());
    }
}
