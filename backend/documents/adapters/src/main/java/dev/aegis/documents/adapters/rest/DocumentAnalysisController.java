package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.adapters.rest.DocumentDto.SubjectDto;
import dev.aegis.documents.application.api.DocumentAnalysisCommands;
import dev.aegis.documents.application.api.DocumentAnalysisCommands.BulkResult;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Automatic analysis of documents: explicit requests (single or a bounded selection), review of generated titles,
 * new names and subject suggestions, and the member's friendly filename. Label suggestions are accepted or rejected
 * through the label endpoints of {@link DocumentsController}.
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "documents")
public class DocumentAnalysisController {
    private final DocumentAnalysisCommands commands;

    public DocumentAnalysisController(DocumentAnalysisCommands commands) {
        this.commands = commands;
    }

    public record AnalysisVersionRequest(long expectedVersion) {
    }

    public record DecisionRequest(boolean accept, long expectedVersion) {
    }

    public record CandidateDecisionRequest(@NotBlank String key, boolean accept, long expectedVersion) {
    }

    public record SubjectDecisionRequest(@NotNull SubjectDto subject, boolean accept, long expectedVersion) {
    }

    /** {@code stem} null or blank returns to the generated name. */
    public record FilenameRequest(@Schema(nullable = true) String stem, long expectedVersion) {
    }

    public record BulkAnalysisRequest(@NotNull List<String> documentIds) {
    }

    public record SkippedDto(String documentId, String reason) {
    }

    public record BulkAnalysisResponse(List<String> requested, List<SkippedDto> skipped) {
    }

    @Operation(operationId = "requestDocumentAnalysis",
            summary = "Analyze an archived document now (first time, again or after a failure)")
    @PostMapping("/{id}/analysis")
    public DocumentDto request(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody AnalysisVersionRequest body) {
        return DocumentDto.from(commands.requestAnalysis(actor, DocumentId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "requestDocumentAnalyses",
            summary = "Analyze a bounded selection of archived documents; never the whole collection")
    @PostMapping("/analysis")
    public BulkAnalysisResponse requestAll(Actor actor, @Valid @RequestBody BulkAnalysisRequest body) {
        BulkResult result = commands.requestAnalyses(actor, body.documentIds().stream().map(DocumentId::of)
                .toList());
        return new BulkAnalysisResponse(result.requested().stream().map(DocumentId::toString).toList(),
                result.skipped().stream().map(s -> new SkippedDto(s.documentId().toString(), s.reason())).toList());
    }

    @Operation(operationId = "decideGeneratedTitle", summary = "Accept or reject/revert the generated title")
    @PostMapping("/{id}/analysis/title")
    public DocumentDto decideTitle(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody DecisionRequest body) {
        return DocumentDto.from(commands.decideTitle(actor, DocumentId.of(id), body.accept(),
                body.expectedVersion()));
    }

    @Operation(operationId = "decideLabelCandidate",
            summary = "Accept (create or reuse and assign) or reject a new organization or topic name")
    @PostMapping("/{id}/analysis/candidates")
    public DocumentDto decideCandidate(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody CandidateDecisionRequest body) {
        return DocumentDto.from(commands.decideCandidate(actor, DocumentId.of(id), body.key(), body.accept(),
                body.expectedVersion()));
    }

    @Operation(operationId = "decideSubjectSuggestion", summary = "Accept or reject a subject suggestion")
    @PostMapping("/{id}/analysis/subjects")
    public DocumentDto decideSubject(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody SubjectDecisionRequest body) {
        return DocumentDto.from(commands.decideSubject(actor, DocumentId.of(id), body.subject().toView(),
                body.accept(), body.expectedVersion()));
    }

    @Operation(operationId = "setDocumentFilename",
            summary = "Set the friendly filename stem; the stable id and verified extension are always added")
    @PutMapping("/{id}/filename")
    public DocumentDto setFilename(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody FilenameRequest body) {
        return DocumentDto.from(commands.setFilename(actor, DocumentId.of(id), body.stem(), body.expectedVersion()));
    }
}
