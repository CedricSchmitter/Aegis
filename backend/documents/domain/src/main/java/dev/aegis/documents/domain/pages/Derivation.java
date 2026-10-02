package dev.aegis.documents.domain.pages;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.MemberId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where the pages of a document created by the page editor came from (ADR-0024): consecutive pages of one source with
 * consecutive page numbers and the same rotation form one range. Page numbers here are one-based, as shown to members
 * ("pages 1–3 of ..."). A range of a stored document whose source was deleted permanently keeps its pages but no
 * longer names the source.
 */
public record Derivation(DocumentId document, UUID pageEdit, MemberId createdBy, Instant createdAt,
        List<Range> ranges) {

    public enum SourceKind { DOCUMENT, SCAN }

    /**
     * Result pages {@code firstPage}..{@code lastPage} are source pages {@code firstSourcePage}..{@code lastSourcePage}
     * of the source, turned by {@code rotation}. {@code source} is null for scanned pages and for a deleted source.
     */
    public record Range(int firstPage, int lastPage, SourceKind kind, DocumentId source, int firstSourcePage,
            int lastSourcePage, int rotation) {
        public Range {
            Objects.requireNonNull(kind, "kind");
            if (firstPage < 1 || lastPage < firstPage || firstSourcePage < 1
                    || lastSourcePage - firstSourcePage != lastPage - firstPage) {
                throw new IllegalArgumentException("inconsistent page range");
            }
            if (kind == SourceKind.SCAN && source != null) {
                throw new IllegalArgumentException("scanned pages have no source document");
            }
        }

        public boolean sourceDeleted() {
            return kind == SourceKind.DOCUMENT && source == null;
        }
    }

    public Derivation {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pageEdit, "pageEdit");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        ranges = List.copyOf(ranges);
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("a derivation needs at least one range");
        }
    }

    /** The derivation of a document created from {@code pages}, collapsing consecutive pages into ranges. */
    public static Derivation of(DocumentId document, UUID pageEdit, MemberId createdBy, Instant createdAt,
            List<PageRef> pages) {
        List<Range> ranges = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= pages.size(); i++) {
            if (i == pages.size() || !continues(pages.get(i - 1), pages.get(i))) {
                PageRef first = pages.get(start);
                int length = i - start;
                ranges.add(new Range(start + 1, start + length, first.fromScan() ? SourceKind.SCAN
                        : SourceKind.DOCUMENT, first.document(), first.pageIndex() + 1, first.pageIndex() + length,
                        first.rotation()));
                start = i;
            }
        }
        return new Derivation(document, pageEdit, createdBy, createdAt, ranges);
    }

    /** The source documents named by the ranges, in order of first use. */
    public List<DocumentId> sources() {
        return ranges.stream().map(Range::source).filter(Objects::nonNull).distinct().toList();
    }

    private static boolean continues(PageRef previous, PageRef next) {
        return Objects.equals(previous.document(), next.document()) && next.pageIndex() == previous.pageIndex() + 1
                && next.rotation() == previous.rotation();
    }
}
