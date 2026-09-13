package dev.aegis.portability.adapters.rest;

import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.ExportSummary;
import dev.aegis.shared.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Export generations on the server's export volume. */
@RestController
@RequestMapping("/api/exports")
@Tag(name = "exports")
public class ExportsController {
    private final CreateExport createExport;
    private final ExportQueries queries;

    public ExportsController(CreateExport createExport, ExportQueries queries) {
        this.createExport = createExport;
        this.queries = queries;
    }

    public record ExportSummaryDto(String generation, Instant createdAt, int documentCount, int taskCount,
            int memberCount, List<String> missingOriginals, @Schema(nullable = true) String location) {
        static ExportSummaryDto from(ExportSummary s) {
            return new ExportSummaryDto(s.generation(), s.createdAt(), s.documentCount(), s.taskCount(),
                    s.memberCount(), s.missingOriginals(), s.location());
        }
    }

    @Operation(operationId = "listExports", summary = "Published export generations, newest first")
    @GetMapping
    public List<ExportSummaryDto> list(Actor actor) {
        return queries.list(actor).stream().map(ExportSummaryDto::from).toList();
    }

    @Operation(operationId = "createExport", summary = "Create and atomically publish a new export generation")
    @PostMapping
    public ResponseEntity<ExportSummaryDto> create(Actor actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ExportSummaryDto.from(createExport.export(actor)));
    }
}
