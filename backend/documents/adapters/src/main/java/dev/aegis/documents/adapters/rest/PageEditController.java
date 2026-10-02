package dev.aegis.documents.adapters.rest;

import dev.aegis.documents.adapters.rest.DocumentDto.SubjectDto;
import dev.aegis.documents.application.api.PageEditCommands;
import dev.aegis.documents.application.api.PageEditCommands.EditRequest;
import dev.aegis.documents.application.api.PageEditCommands.OutputRequest;
import dev.aegis.documents.application.api.PageEditCommands.PageEditView;
import dev.aegis.documents.application.api.PageEditCommands.PageRequest;
import dev.aegis.documents.application.api.PageQueries;
import dev.aegis.documents.application.api.PageQueries.DerivationsView;
import dev.aegis.documents.application.api.PageQueries.PagesView;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
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
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The page editor (ADR-0024): pages and previews of a document, where documents' pages came from, and operations that
 * create new documents from pages. Sources are never changed.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "page-editor")
public class PageEditController {
    private final PageQueries pages;
    private final PageEditCommands edits;

    public PageEditController(PageQueries pages, PageEditCommands edits) {
        this.pages = pages;
        this.edits = edits;
    }

    /** Size as displayed, in points. */
    public record PageDto(int index, float width, float height) {
    }

    /**
     * {@code refusal}: why the document cannot be used in an edit (for example {@code pageEdit.source.encrypted}),
     * or null. {@code warnings}: what new documents would not carry over ({@code SIGNED}, {@code EMBEDDED_FILES}).
     */
    public record PagesDto(String documentId, @Schema(allowableValues = {"PDF", "PHOTO"}) String sourceKind,
            List<PageDto> pages, List<String> warnings, @Schema(nullable = true) String refusal) {
        static PagesDto from(PagesView view) {
            return new PagesDto(view.document().toString(), view.sourceKind(), view.pages().stream()
                    .map(page -> new PageDto(page.index(), page.width(), page.height())).toList(), view.warnings(),
                    view.refusal());
        }
    }

    /** A page of a stored document ({@code DOCUMENT} with {@code documentId}) or of the scanned pages. */
    public record PageRefDto(@NotBlank @Schema(allowableValues = {"DOCUMENT", "SCAN"}) String source,
            @Schema(nullable = true) String documentId, int pageIndex, int rotation) {
    }

    public record OutputDto(@NotBlank String title, boolean titleChosen, @Schema(nullable = true) String categoryId,
            @NotNull List<SubjectDto> subjects, @NotNull List<@Valid PageRefDto> pages) {
    }

    /** {@code id}: chosen by the client when the plan is started, so a repeated request finds the operation. */
    public record PageEditRequestDto(@NotBlank String id, @NotNull List<@Valid OutputDto> outputs) {
    }

    public record ResultDto(int output, String documentId, String title, boolean existing, boolean trashed) {
    }

    public record PageEditDto(String id,
            @Schema(allowableValues = {"REQUESTED", "COMPLETED", "FAILED"}) String state,
            @Schema(nullable = true) String failureCode, String requestedBy, Instant requestedAt,
            @Schema(nullable = true) Instant finishedAt, List<ResultDto> results) {
        static PageEditDto from(PageEditView view) {
            return new PageEditDto(view.id().toString(), view.state(), view.failureCode(),
                    view.requestedBy().toString(), view.requestedAt(), view.finishedAt(), view.results().stream()
                            .map(result -> new ResultDto(result.output(), result.document().toString(),
                                    result.title(), result.existing(), result.trashed()))
                            .toList());
        }
    }

    public record SourceRangeDto(int firstPage, int lastPage,
            @Schema(allowableValues = {"DOCUMENT", "SCAN"}) String sourceKind,
            @Schema(nullable = true) String sourceDocumentId, @Schema(nullable = true) String sourceTitle,
            @Schema(nullable = true, allowableValues = {"AVAILABLE", "TRASHED", "DELETED"}) String sourceState,
            int firstSourcePage, int lastSourcePage, int rotation) {
    }

    public record OriginDto(String pageEditId, String createdBy, Instant createdAt, List<SourceRangeDto> ranges) {
    }

    public record PageSpanDto(int firstPage, int lastPage) {
    }

    public record DerivedDto(String documentId, String title, boolean trashed, String createdBy, Instant createdAt,
            List<PageSpanDto> sourcePages) {
    }

    /** {@code origin} is null for a document the page editor did not create. */
    public record DerivationsDto(@Schema(nullable = true) OriginDto origin, List<DerivedDto> derived) {
        static DerivationsDto from(DerivationsView view) {
            OriginDto origin = view.origin() == null ? null : new OriginDto(view.origin().pageEdit().toString(),
                    view.origin().createdBy().toString(), view.origin().createdAt(), view.origin().ranges().stream()
                            .map(range -> new SourceRangeDto(range.firstPage(), range.lastPage(),
                                    range.sourceKind(), range.source() == null ? null : range.source().toString(),
                                    range.sourceTitle(), range.sourceState(), range.firstSourcePage(),
                                    range.lastSourcePage(), range.rotation()))
                            .toList());
            return new DerivationsDto(origin, view.derived().stream().map(derived -> new DerivedDto(
                    derived.document().toString(), derived.title(), derived.trashed(),
                    derived.createdBy().toString(), derived.createdAt(), derived.sourcePages().stream()
                            .map(span -> new PageSpanDto(span.firstPage(), span.lastPage())).toList()))
                    .toList());
        }
    }

    @Operation(operationId = "getDocumentPages", summary = "Pages of a document for the page editor; may fetch the "
            + "original from the archive once")
    @GetMapping("/documents/{id}/pages")
    public PagesDto pages(Actor actor, @PathVariable("id") String id) {
        return PagesDto.from(pages.pages(actor, DocumentId.of(id)));
    }

    @Operation(operationId = "getPagePreview", summary = "A small JPEG of one page of a document")
    @GetMapping(value = "/documents/{id}/pages/{index}/preview", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> preview(Actor actor, @PathVariable("id") String id,
            @PathVariable("index") int index) {
        byte[] jpeg = pages.preview(actor, DocumentId.of(id), index);
        // A document's original never changes, so neither do its page previews.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    @Operation(operationId = "getDocumentDerivations", summary = "Where a document's pages came from and which "
            + "documents were created from its pages")
    @GetMapping("/documents/{id}/derivations")
    public DerivationsDto derivations(Actor actor, @PathVariable("id") String id) {
        return DerivationsDto.from(pages.derivations(actor, DocumentId.of(id)));
    }

    @Operation(operationId = "requestPageEdit", summary = "Create new documents from pages of stored documents and "
            + "newly scanned pages; answered once the plan and the scan are stored durably")
    @PostMapping(value = "/page-edits", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PageEditDto> request(Actor actor, @Valid @RequestPart("plan") PageEditRequestDto plan,
            @RequestPart(name = "scan", required = false) MultipartFile scan) {
        List<OutputRequest> outputs = plan.outputs().stream().map(PageEditController::output).toList();
        boolean withScan = scan != null && !scan.isEmpty();
        try (InputStream content = withScan ? scan.getInputStream() : null) {
            PageEditView view = edits.request(actor, new EditRequest(RestIds.required(plan.id()), outputs, content,
                    withScan ? scan.getSize() : 0));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(PageEditDto.from(view));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Operation(operationId = "getPageEdit", summary = "State and results of a page editor operation")
    @GetMapping("/page-edits/{id}")
    public PageEditDto get(Actor actor, @PathVariable("id") String id) {
        return PageEditDto.from(edits.get(actor, RestIds.required(id)));
    }

    private static OutputRequest output(OutputDto output) {
        List<PageRequest> pages = output.pages().stream().map(page -> switch (page.source()) {
            case "DOCUMENT" -> new PageRequest(DocumentId.of(page.documentId()), page.pageIndex(), page.rotation());
            case "SCAN" -> new PageRequest(null, page.pageIndex(), page.rotation());
            default -> throw new InvalidInputException("validation.failed", "Unknown page source " + page.source());
        }).toList();
        return new OutputRequest(output.title(), output.titleChosen(), RestIds.optional(output.categoryId()),
                output.subjects().stream().map(SubjectDto::toView).toList(), pages);
    }
}
