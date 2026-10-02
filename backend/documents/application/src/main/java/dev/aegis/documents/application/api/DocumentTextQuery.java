package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.OptionalInt;

/**
 * The text the archive recognized in a document, for readers that quote the shared collection, such as the household
 * assistant. The same household authorization applies as for the original. Reads the archive; call it outside
 * database transactions.
 */
public interface DocumentTextQuery {
    /**
     * When {@code paged}, {@code pages} holds the text of every page of the document's PDF in order, so a position in
     * the text names its page. Otherwise it holds one entry, the archive's OCR text, whose page boundaries are
     * unknown. The text is empty when the archive recognized none.
     */
    record DocumentText(DocumentId id, String title, List<String> pages, boolean paged) {
        /** Joins the pages in {@link #text()}; a line break, so words hyphenated across pages still join. */
        public static final String PAGE_SEPARATOR = "\n";

        public DocumentText {
            pages = List.copyOf(pages);
            if (pages.isEmpty()) {
                throw new IllegalArgumentException("a document text has at least one entry");
            }
            if (!paged && pages.size() != 1) {
                throw new IllegalArgumentException("a text without pages has exactly one entry");
            }
        }

        public static DocumentText unpaged(DocumentId id, String title, String text) {
            return new DocumentText(id, title, List.of(text), false);
        }

        /** The whole text, pages joined by {@link #PAGE_SEPARATOR}; positions below refer to it. */
        public String text() {
            return String.join(PAGE_SEPARATOR, pages);
        }

        /** The position in {@link #text()} where the 1-based {@code page} starts. */
        public int pageStart(int page) {
            if (page < 1 || page > pages.size()) {
                throw new IndexOutOfBoundsException("page " + page + " of " + pages.size());
            }
            int start = 0;
            for (int i = 0; i < page - 1; i++) {
                start += pages.get(i).length() + PAGE_SEPARATOR.length();
            }
            return start;
        }

        /**
         * The 1-based page holding the character at {@code position} of {@link #text()}; a separator belongs to the
         * page it ends. Empty when the pages are unknown.
         */
        public OptionalInt pageAt(int position) {
            if (!paged) {
                return OptionalInt.empty();
            }
            int end = 0;
            for (int i = 0; i < pages.size(); i++) {
                end += pages.get(i).length() + PAGE_SEPARATOR.length();
                if (position < end) {
                    return OptionalInt.of(i + 1);
                }
            }
            return OptionalInt.of(pages.size());
        }
    }

    /**
     * @throws dev.aegis.shared.NotFoundException for an unknown document
     * @throws dev.aegis.shared.TrashedException {@code document.trashed} for a document in the trash
     * @throws dev.aegis.shared.InvalidInputException {@code document.notArchived} before the archive holds it
     * @throws ContentUnavailableException when the archive cannot deliver the text right now
     */
    DocumentText text(Actor actor, DocumentId id);
}
