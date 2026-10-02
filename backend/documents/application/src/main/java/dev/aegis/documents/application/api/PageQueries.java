package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The pages of a document for the page editor, their previews and where documents' pages came from (ADR-0024). */
public interface PageQueries {
    String WARNING_SIGNED = "SIGNED";
    String WARNING_EMBEDDED_FILES = "EMBEDDED_FILES";

    /** Size of a page as displayed, in points. */
    record PageView(int index, float width, float height) {
    }

    /**
     * {@code sourceKind} is {@code PDF} or {@code PHOTO}. {@code refusal} is the code why the document cannot be used
     * in an edit right now (encrypted, unreadable, a photo not archived yet, in the trash), or null; a refused
     * document lists no pages unless it is only trashed. {@code warnings} name what new documents would not carry
     * over ({@value #WARNING_SIGNED}, {@value #WARNING_EMBEDDED_FILES}).
     */
    record PagesView(DocumentId document, String sourceKind, List<PageView> pages, List<String> warnings,
            String refusal) {
    }

    /** A range of a created document's pages and where it came from; one-based page numbers. */
    record SourceRangeView(int firstPage, int lastPage, String sourceKind, DocumentId source, String sourceTitle,
            String sourceState, int firstSourcePage, int lastSourcePage, int rotation) {
        public static final String AVAILABLE = "AVAILABLE";
        public static final String TRASHED = "TRASHED";
        public static final String DELETED = "DELETED";
    }

    /** How a document was created by the page editor: by whom, when, and from which pages. */
    record OriginView(UUID pageEdit, MemberId createdBy, Instant createdAt, List<SourceRangeView> ranges) {
    }

    /** A document created from pages of the document asked about, and which of its pages it took. */
    record DerivedView(DocumentId document, String title, boolean trashed, MemberId createdBy, Instant createdAt,
            List<PageSpan> sourcePages) {
    }

    /** One-based inclusive page numbers. */
    record PageSpan(int firstPage, int lastPage) {
    }

    /** {@code origin} is null for a document that was not created by the page editor. */
    record DerivationsView(OriginView origin, List<DerivedView> derived) {
    }

    /**
     * The pages of a document. Reading them may fetch the original from the archive once.
     *
     * @throws dev.aegis.shared.NotFoundException when the document does not exist
     */
    PagesView pages(Actor actor, DocumentId id);

    /**
     * A small JPEG of one page.
     *
     * @throws dev.aegis.shared.NotFoundException with resource {@code page} for a page the document does not have
     */
    byte[] preview(Actor actor, DocumentId id, int pageIndex);

    DerivationsView derivations(Actor actor, DocumentId id);
}
