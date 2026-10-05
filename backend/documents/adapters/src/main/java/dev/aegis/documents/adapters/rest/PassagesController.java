package dev.aegis.documents.adapters.rest;
import dev.aegis.documents.application.api.*;
import dev.aegis.documents.adapters.rest.PassageDtos.*;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
/** Session actor is supplied by the existing authenticated argument resolver. */
@RestController
@RequestMapping("/api/documents/passages")
@Tag(name="document-passages")
public class PassagesController {
    private final PassageQueries queries;
    private final PassageCommands commands;
    public PassagesController(PassageQueries queries,PassageCommands commands) { this.queries=queries; this.commands=commands; }
    @GetMapping("/coverage") @Operation(summary="Passage index coverage and source freshness")
    public PassageCoverageDto coverage(Actor actor) { return PassageCoverageDto.from(queries.coverage(actor)); }
    @GetMapping @Operation(summary="Search original passages with bounded lexical retrieval")
    public PassagePageDto search(Actor actor,@RequestParam String query,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return PassagePageDto.from(queries.search(actor,query,cursor,limit));
    }
    @GetMapping("/{id}/status") @Operation(summary="Passage indexing status for an active document")
    public PassageStatusDto status(Actor actor,@PathVariable String id) { return PassageStatusDto.from(queries.status(actor,DocumentId.of(id))); }
    @GetMapping("/{id}/{revision}/{ordinal}") @Operation(summary="Read a current original passage with provenance")
    public PassageDto read(Actor actor,@PathVariable String id,@PathVariable String revision,@PathVariable int ordinal) {
        return PassageDto.from(queries.read(actor,DocumentId.of(id),revision,ordinal));
    }
    @PostMapping("/{id}/retry") @Operation(summary="Request source revalidation and passage reindexing")
    public void retry(Actor actor,@PathVariable String id) { commands.retry(actor,DocumentId.of(id)); }
    @PostMapping("/rebuild") @Operation(summary="Request a bounded restart-safe rebuild of derived passages")
    public void rebuild(Actor actor) { commands.rebuild(actor); }
}
