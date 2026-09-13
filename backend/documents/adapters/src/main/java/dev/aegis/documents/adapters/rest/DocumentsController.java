package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.adapters.rest.DocumentDto.SubjectDto;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.documents.application.api.UploadDocument.UploadResult;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Shared document collection: upload, list/search, original access and metadata changes. */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "documents")
public class DocumentsController {
    private final UploadDocument upload;
    private final DocumentQueries queries;
    private final DocumentCommands commands;

    public DocumentsController(UploadDocument upload, DocumentQueries queries, DocumentCommands commands) {
        this.upload = upload;
        this.queries = queries;
        this.commands = commands;
    }

    public record DocumentListResponse(List<DocumentDto> items, ContentSearchState contentSearch,
            long totalDocuments) {
    }

    public record UploadResponse(DocumentDto document, boolean duplicate) {
    }

    public record SubjectsRequest(@NotNull List<SubjectDto> subjects, long expectedVersion) {
    }

    public record RenameRequest(@NotBlank String title, long expectedVersion) {
    }

    @Operation(operationId = "searchDocuments", summary = "List or search the shared document collection")
    @GetMapping
    public DocumentListResponse search(Actor actor, @RequestParam(name = "query", required = false) String query) {
        DocumentSearchResult result = queries.search(actor, query);
        return new DocumentListResponse(result.items().stream().map(DocumentDto::from).toList(),
                result.contentSearch(), result.totalDocuments());
    }

    @Operation(operationId = "uploadDocument", summary = "Upload a PDF; answers only after durable storage")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(Actor actor, @RequestPart("file") MultipartFile file,
            @RequestParam(name = "subjects", required = false) List<String> subjects) {
        if (file.isEmpty()) {
            throw new InvalidInputException("document.empty", "Uploaded file is empty");
        }
        try (InputStream content = file.getInputStream()) {
            UploadResult result = upload.upload(actor, new UploadCommand(file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content, parseSubjects(subjects)));
            return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(new UploadResponse(DocumentDto.from(result.document()), result.duplicate()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(operationId = "getDocument", summary = "Document details including processing state")
    @GetMapping("/{id}")
    public DocumentDto get(Actor actor, @PathVariable("id") String id) {
        return DocumentDto.from(queries.get(actor, DocumentId.of(id)));
    }

    @Operation(operationId = "downloadOriginal", summary = "Original bytes, unchanged")
    @GetMapping(value = "/{id}/original", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> original(Actor actor, @PathVariable("id") String id,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline) {
        OriginalContent content = queries.original(actor, DocumentId.of(id));
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(content.filename(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Aegis-Original-Source", content.source())
                .contentType(MediaType.parseMediaType(content.contentType())).body(content.bytes());
    }

    @Operation(operationId = "retryArchival", summary = "Re-queue archival of a failed document")
    @PostMapping("/{id}/retry")
    public DocumentDto retry(Actor actor, @PathVariable("id") String id) {
        return DocumentDto.from(commands.retryArchival(actor, DocumentId.of(id)));
    }

    @Operation(operationId = "changeSubjects", summary = "Replace the subject metadata of a document")
    @PutMapping("/{id}/subjects")
    public DocumentDto changeSubjects(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody SubjectsRequest body) {
        List<SubjectView> views = body.subjects().stream().map(SubjectDto::toView).toList();
        return DocumentDto.from(commands.changeSubjects(actor, DocumentId.of(id), views, body.expectedVersion()));
    }

    @Operation(operationId = "renameDocument", summary = "Change the display title of a document")
    @PutMapping("/{id}/title")
    public DocumentDto rename(Actor actor, @PathVariable("id") String id, @Valid @RequestBody RenameRequest body) {
        return DocumentDto.from(commands.rename(actor, DocumentId.of(id), body.title(), body.expectedVersion()));
    }

    /** Subjects arrive as form values: HOUSE, HOUSEHOLD or MEMBER:{memberId}. */
    static List<SubjectView> parseSubjects(List<String> raw) {
        List<SubjectView> views = new ArrayList<>();
        if (raw == null) {
            return views;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String[] parts = value.trim().split(":", 2);
            MemberId member = null;
            if (parts.length == 2) {
                try {
                    member = MemberId.of(parts[1]);
                } catch (IllegalArgumentException e) {
                    throw new InvalidInputException("subject.member.invalid", "Invalid member id in subject");
                }
            }
            views.add(new SubjectView(parts[0].toUpperCase(java.util.Locale.ROOT), member));
        }
        return views;
    }
}
