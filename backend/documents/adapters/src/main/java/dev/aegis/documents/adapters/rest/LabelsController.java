package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.shared.Actor;
import dev.aegis.shared.NotFoundException;
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

/** The shared label vocabulary: chips for document type, organization, topics and free-form tags. */
@RestController
@RequestMapping("/api/labels")
@Tag(name = "labels")
public class LabelsController {
    private final CatalogQueries queries;
    private final LabelCommands commands;

    public LabelsController(CatalogQueries queries, LabelCommands commands) {
        this.queries = queries;
        this.commands = commands;
    }

    public record CreateLabelRequest(@Schema(nullable = true) String id, @NotBlank String facet,
            @NotBlank String name) {
    }

    public record RenameLabelRequest(@NotBlank String name, long expectedVersion) {
    }

    public record AliasesRequest(@NotNull List<String> aliases, long expectedVersion) {
    }

    /** Named explicitly because planning and contracts publish their own {@code VersionRequest}. */
    @Schema(name = "LabelVersionRequest")
    public record VersionRequest(long expectedVersion) {
    }

    public record MergeRequest(@NotBlank String targetId, long expectedVersion) {
    }

    public record FacetRequest(@NotBlank String facet, long expectedVersion) {
    }

    public record MergeResponse(LabelDto target, int documentsUpdated, int foldersUpdated) {
    }

    @Operation(operationId = "listLabels", summary = "Whole vocabulary with usage counts")
    @GetMapping
    public List<LabelDto> list(Actor actor) {
        return queries.labels(actor).stream().map(LabelDto::from).toList();
    }

    @Operation(operationId = "resolveLabel", summary = "Find a label by name or alias within one facet")
    @GetMapping("/resolve")
    public LabelDto resolve(Actor actor, @RequestParam("facet") String facet, @RequestParam("name") String name) {
        return queries.resolveLabel(actor, facet, name).map(LabelDto::from)
                .orElseThrow(() -> new NotFoundException("label", facet + ":" + name));
    }

    @Operation(operationId = "createLabel", summary = "Create a label; a client id makes retries idempotent")
    @PostMapping
    public ResponseEntity<LabelDto> create(Actor actor, @Valid @RequestBody CreateLabelRequest body) {
        LabelDto created = LabelDto.from(commands.create(actor, RestIds.optional(body.id()), body.facet(),
                body.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(operationId = "renameLabel", summary = "Rename a label without losing its assignments")
    @PutMapping("/{id}/name")
    public LabelDto rename(Actor actor, @PathVariable("id") String id, @Valid @RequestBody RenameLabelRequest body) {
        return LabelDto.from(commands.rename(actor, RestIds.required(id), body.name(), body.expectedVersion()));
    }

    @Operation(operationId = "replaceLabelAliases", summary = "Replace the alternative spellings of a label")
    @PutMapping("/{id}/aliases")
    public LabelDto aliases(Actor actor, @PathVariable("id") String id, @Valid @RequestBody AliasesRequest body) {
        return LabelDto.from(commands.replaceAliases(actor, RestIds.required(id), body.aliases(),
                body.expectedVersion()));
    }

    @Operation(operationId = "retireLabel", summary = "Stop offering a label for new assignments")
    @PostMapping("/{id}/retire")
    public LabelDto retire(Actor actor, @PathVariable("id") String id, @Valid @RequestBody VersionRequest body) {
        return LabelDto.from(commands.retire(actor, RestIds.required(id), body.expectedVersion()));
    }

    @Operation(operationId = "reactivateLabel", summary = "Offer a retired label again")
    @PostMapping("/{id}/reactivate")
    public LabelDto reactivate(Actor actor, @PathVariable("id") String id, @Valid @RequestBody VersionRequest body) {
        return LabelDto.from(commands.reactivate(actor, RestIds.required(id), body.expectedVersion()));
    }

    @Operation(operationId = "mergeLabel",
            summary = "Fold a label into another one; assignments and folder mappings follow")
    @PostMapping("/{id}/merge")
    public MergeResponse merge(Actor actor, @PathVariable("id") String id, @Valid @RequestBody MergeRequest body) {
        var result = commands.merge(actor, RestIds.required(id), RestIds.required(body.targetId()),
                body.expectedVersion());
        return new MergeResponse(LabelDto.from(result.target()), result.documentsUpdated(),
                result.foldersUpdated());
    }

    @Operation(operationId = "convertLabel", summary = "Turn a tag into an organization or topic")
    @PostMapping("/{id}/facet")
    public LabelDto convert(Actor actor, @PathVariable("id") String id, @Valid @RequestBody FacetRequest body) {
        return LabelDto.from(commands.convert(actor, RestIds.required(id), body.facet(), body.expectedVersion()));
    }

    @Operation(operationId = "deleteLabel", summary = "Delete an unused label; documents are never deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Actor actor, @PathVariable("id") String id,
            @RequestParam("expectedVersion") long expectedVersion) {
        commands.delete(actor, RestIds.required(id), expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
