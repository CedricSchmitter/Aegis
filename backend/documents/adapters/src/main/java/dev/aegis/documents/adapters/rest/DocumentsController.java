package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.adapters.rest.DocumentDto.SubjectDto;
import dev.aegis.documents.application.api.ArchivePdf;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.ClassificationCommands.Placement;
import dev.aegis.documents.application.api.ContentSearchState;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentSearchRequest;
import dev.aegis.documents.application.api.DocumentSearchResult;
import dev.aegis.documents.application.api.DocumentThumbnail;
import dev.aegis.documents.application.api.FacetCounts;
import dev.aegis.documents.application.api.OriginalContent;
import dev.aegis.documents.application.api.SubjectView;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.api.UploadDocument.UploadCommand;
import dev.aegis.documents.application.api.UploadDocument.UploadResult;
import dev.aegis.documents.domain.naming.FriendlyFilename;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import dev.aegis.shared.MemberId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.CacheControl;
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

/**
 * Shared document collection: upload, structured search, original access, folder placement and label editing.
 * Search combines free text, folder scope and facets; every parameter is part of the URL so a filtered view can be
 * shared or bookmarked.
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "documents")
public class DocumentsController {
    private final UploadDocument upload;
    private final DocumentQueries queries;
    private final DocumentCommands commands;
    private final ClassificationCommands classification;

    public DocumentsController(UploadDocument upload, DocumentQueries queries, DocumentCommands commands,
            ClassificationCommands classification) {
        this.upload = upload;
        this.queries = queries;
        this.commands = commands;
        this.classification = classification;
    }

    /**
     * {@code facets} carries one value map per facet group with document counts, plus the number of documents
     * without a value in that group. {@code notContentIndexed} counts matching documents that are not archived yet.
     */
    public record DocumentListResponse(List<DocumentDto> items, ContentSearchState contentSearch,
            long totalDocuments, long matchingDocuments, int page, int size, FacetCounts facets,
            long notContentIndexed) {
    }

    public record UploadResponse(DocumentDto document, boolean duplicate) {
    }

    public record SubjectsRequest(@NotNull List<SubjectDto> subjects, long expectedVersion) {
    }

    public record RenameRequest(@NotBlank String title, long expectedVersion) {
    }

    public record CategoryRequest(@Schema(nullable = true) String categoryId, long expectedVersion) {
    }

    public record LabelsRequest(@NotNull List<String> labelIds, long expectedVersion) {
    }

    public record PlacementRequest(@Schema(nullable = true) String categoryId,
            @NotNull List<PlacementItem> documents) {
    }

    public record PlacementItem(@NotBlank String documentId, long expectedVersion) {
    }

    public record PlacementResponse(int moved) {
    }

    @Operation(operationId = "searchDocuments",
            summary = "List or search the shared collection by text, folder and facets")
    @GetMapping
    public DocumentListResponse search(Actor actor,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "subfolders", defaultValue = "true") boolean subfolders,
            @RequestParam(name = "type", required = false) List<String> documentTypes,
            @RequestParam(name = "organization", required = false) List<String> organizations,
            @RequestParam(name = "topic", required = false) List<String> topics,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "subject", required = false) List<String> subjects,
            @RequestParam(name = "topicMatch", defaultValue = "ANY") String topicMatch,
            @RequestParam(name = "tagMatch", defaultValue = "ANY") String tagMatch,
            @RequestParam(name = "missing", required = false) List<String> missing,
            @RequestParam(name = "sort", defaultValue = "NEWEST") String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        boolean unsorted = "unsorted".equalsIgnoreCase(category);
        DocumentSearchRequest request = new DocumentSearchRequest(query,
                unsorted ? null : RestIds.optional(category), subfolders, unsorted,
                RestIds.all(documentTypes), RestIds.all(organizations), RestIds.all(topics), RestIds.all(tags),
                parseSubjects(subjects), matchAll(topicMatch), matchAll(tagMatch), missingGroups(missing),
                sort == null ? null : sort.toUpperCase(Locale.ROOT), page, size);
        DocumentSearchResult result = queries.search(actor, request);
        return new DocumentListResponse(result.items().stream().map(DocumentDto::from).toList(),
                result.contentSearch(), result.totalDocuments(), result.matchingDocuments(), result.page(),
                result.size(), result.facets(), result.notContentIndexed());
    }

    @Operation(operationId = "uploadDocument", summary = "Upload a PDF or a photo (JPEG, PNG, WebP, HEIC); the type "
            + "is recognized from the content, and the answer comes only after durable storage")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(Actor actor, @RequestPart("file") MultipartFile file,
            @RequestParam(name = "subjects", required = false) List<String> subjects,
            @RequestParam(name = "categoryId", required = false) String categoryId) {
        if (file.isEmpty()) {
            throw new InvalidInputException("document.empty", "Uploaded file is empty");
        }
        try (InputStream content = file.getInputStream()) {
            UploadResult result = upload.upload(actor, new UploadCommand(file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content, parseSubjects(subjects),
                    RestIds.optional(categoryId)));
            return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(new UploadResponse(DocumentDto.from(result.document()), result.duplicate()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(operationId = "listTrashedDocuments",
            summary = "Documents in the trash, most recently trashed first")
    @GetMapping("/trash")
    public List<DocumentDto> trashed(Actor actor) {
        return queries.trashed(actor).stream().map(DocumentDto::from).toList();
    }

    @Operation(operationId = "trashDocument",
            summary = "Move a document to the trash; nothing is deleted and it can be restored")
    @PostMapping("/{id}/trash")
    public DocumentDto trash(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody LabelsController.VersionRequest body) {
        return DocumentDto.from(commands.moveToTrash(actor, DocumentId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "restoreDocument", summary = "Bring a document back from the trash")
    @PostMapping("/{id}/restore")
    public DocumentDto restore(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody LabelsController.VersionRequest body) {
        return DocumentDto.from(commands.restoreFromTrash(actor, DocumentId.of(id), body.expectedVersion()));
    }

    @Operation(operationId = "purgeDocument",
            summary = "Delete a trashed document permanently, including its archive original (ADR-0021)",
            description = "202 with the document while the deletion runs in the background; it can no longer be "
                    + "restored. 204 once it is gone, so a repeated request after a lost response is harmless.")
    @PostMapping("/{id}/purge")
    public ResponseEntity<DocumentDto> purge(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody LabelsController.VersionRequest body) {
        return commands.purge(actor, DocumentId.of(id), body.expectedVersion())
                .map(summary -> ResponseEntity.status(HttpStatus.ACCEPTED).body(DocumentDto.from(summary)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(operationId = "getDocument",
            summary = "Document details including processing state, labels and trash state")
    @GetMapping("/{id}")
    public DocumentDto get(Actor actor, @PathVariable("id") String id) {
        return DocumentDto.from(queries.get(actor, DocumentId.of(id)));
    }

    @Operation(operationId = "downloadOriginal",
            summary = "Original bytes, unchanged, named with the friendly filename")
    @GetMapping(value = "/{id}/original", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> original(Actor actor, @PathVariable("id") String id,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline) {
        OriginalContent content = queries.original(actor, DocumentId.of(id));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(inline,
                content.filename()))
                .header("X-Aegis-Original-Source", content.source())
                .contentType(MediaType.parseMediaType(content.contentType())).body(content.bytes());
    }

    @Operation(operationId = "downloadArchivePdf",
            summary = "Searchable PDF the archive generated from the original, for example from a photo")
    @GetMapping(value = "/{id}/archive-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> archivePdf(Actor actor, @PathVariable("id") String id,
            @RequestParam(name = "inline", defaultValue = "false") boolean inline) {
        ArchivePdf pdf = queries.archivePdf(actor, DocumentId.of(id));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(inline,
                pdf.filename())).contentType(MediaType.APPLICATION_PDF).body(pdf.bytes());
    }

    /**
     * The archive's image of the first page. It never changes for a document, so the browser may keep it privately
     * for a day instead of asking the archive again for every list it appears in.
     */
    @Operation(operationId = "getDocumentThumbnail",
            summary = "Image of the first page rendered by the archive, for previews; 404 until the document is archived")
    @GetMapping(value = "/{id}/thumbnail", produces = {"image/webp", MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_JPEG_VALUE})
    public ResponseEntity<byte[]> thumbnail(Actor actor, @PathVariable("id") String id) {
        DocumentThumbnail thumbnail = queries.thumbnail(actor, DocumentId.of(id));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .contentType(MediaType.parseMediaType(thumbnail.contentType())).body(thumbnail.bytes());
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

    @Operation(operationId = "placeDocument", summary = "File a document into a folder; empty means Unsorted")
    @PutMapping("/{id}/category")
    public DocumentDto place(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody CategoryRequest body) {
        return DocumentDto.from(classification.place(actor, DocumentId.of(id),
                RestIds.optional(body.categoryId()), body.expectedVersion()));
    }

    @Operation(operationId = "placeDocuments", summary = "File several documents into the same folder")
    @PostMapping("/placement")
    public PlacementResponse placeAll(Actor actor, @Valid @RequestBody PlacementRequest body) {
        List<Placement> placements = body.documents().stream()
                .map(item -> new Placement(DocumentId.of(item.documentId()), item.expectedVersion())).toList();
        return new PlacementResponse(classification.placeAll(actor, placements,
                RestIds.optional(body.categoryId())));
    }

    @Operation(operationId = "changeDocumentLabels",
            summary = "Replace the own labels of a document across all facets")
    @PutMapping("/{id}/labels")
    public DocumentDto changeLabels(Actor actor, @PathVariable("id") String id,
            @Valid @RequestBody LabelsRequest body) {
        return DocumentDto.from(classification.setLabels(actor, DocumentId.of(id), RestIds.all(body.labelIds()),
                body.expectedVersion()));
    }

    @Operation(operationId = "acceptLabelSuggestion", summary = "Confirm an automatically proposed label")
    @PostMapping("/{id}/labels/{labelId}/accept")
    public DocumentDto accept(Actor actor, @PathVariable("id") String id, @PathVariable("labelId") String labelId,
            @Valid @RequestBody LabelsController.VersionRequest body) {
        return DocumentDto.from(classification.acceptSuggestion(actor, DocumentId.of(id),
                RestIds.required(labelId), body.expectedVersion()));
    }

    @Operation(operationId = "rejectLabelSuggestion",
            summary = "Reject an automatically proposed label; the decision is remembered")
    @PostMapping("/{id}/labels/{labelId}/reject")
    public DocumentDto reject(Actor actor, @PathVariable("id") String id, @PathVariable("labelId") String labelId,
            @Valid @RequestBody LabelsController.VersionRequest body) {
        return DocumentDto.from(classification.rejectSuggestion(actor, DocumentId.of(id),
                RestIds.required(labelId), body.expectedVersion()));
    }

    /**
     * Content-Disposition with an ASCII {@code filename} fallback and the full UTF-8 name as RFC 5987
     * {@code filename*}. The name is already sanitized; percent-encoding keeps quotes, separators and line breaks out
     * of the header in any case.
     */
    static String contentDisposition(boolean inline, String filename) {
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return (inline ? "inline" : "attachment") + "; filename=\"" + FriendlyFilename.asciiFallback(filename)
                + "\"; filename*=UTF-8''" + encoded;
    }

    private static boolean matchAll(String value) {
        return "ALL".equalsIgnoreCase(value);
    }

    private static Set<String> missingGroups(List<String> raw) {
        Set<String> groups = new LinkedHashSet<>();
        if (raw == null) {
            return groups;
        }
        raw.stream().filter(value -> value != null && !value.isBlank())
                .forEach(value -> groups.add(value.trim().toUpperCase(Locale.ROOT)));
        return groups;
    }

    /** Subjects arrive as form or query values: HOUSE or MEMBER:{memberId}. */
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
            views.add(new SubjectView(parts[0].toUpperCase(Locale.ROOT), member));
        }
        return views;
    }
}
