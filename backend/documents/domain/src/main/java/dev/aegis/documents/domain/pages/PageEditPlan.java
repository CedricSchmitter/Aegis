package dev.aegis.documents.domain.pages;

import dev.aegis.shared.DocumentId;
import dev.aegis.shared.InvalidInputException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a member asks the page editor to create (ADR-0024): an ordered list of new documents, each an ordered list of
 * pages. Splitting, merging, reordering, rotating and removing pages are all expressed this way; a page may appear in
 * more than one output. The plan never describes a change to a source.
 */
public record PageEditPlan(List<PageEditOutput> outputs) {

    /** Configured bounds of one request. */
    public record Limits(int maxOutputs, int maxPagesPerOutput, int maxSources, int maxNewPages) {
        public Limits {
            if (maxOutputs < 1 || maxPagesPerOutput < 1 || maxSources < 1 || maxNewPages < 0) {
                throw new IllegalArgumentException("page edit limits must be positive");
            }
        }
    }

    public PageEditPlan {
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        if (outputs.isEmpty()) {
            throw new InvalidInputException("pageEdit.plan.empty", "The plan creates no document");
        }
    }

    /** The stored documents whose pages the plan uses, in order of first use. */
    public Set<DocumentId> sourceDocuments() {
        Set<DocumentId> sources = new LinkedHashSet<>();
        for (PageEditOutput output : outputs) {
            for (PageRef page : output.pages()) {
                page.source().ifPresent(sources::add);
            }
        }
        return sources;
    }

    public boolean usesScan() {
        return outputs.stream().flatMap(output -> output.pages().stream()).anyMatch(PageRef::fromScan);
    }

    /**
     * Checks the plan against the limits and the page counts of its sources.
     *
     * @param pageCounts pages of every source document the plan uses
     * @param scanPages pages of the scan attached to the request, 0 when there is none
     */
    public void validate(Limits limits, Map<DocumentId, Integer> pageCounts, int scanPages) {
        if (outputs.size() > limits.maxOutputs()) {
            throw new InvalidInputException("pageEdit.limit.outputs",
                    "At most " + limits.maxOutputs() + " new documents per request");
        }
        if (sourceDocuments().size() > limits.maxSources()) {
            throw new InvalidInputException("pageEdit.limit.sources",
                    "At most " + limits.maxSources() + " source documents per request");
        }
        if (scanPages > limits.maxNewPages()) {
            throw new InvalidInputException("pageEdit.limit.newPages",
                    "At most " + limits.maxNewPages() + " new pages per request");
        }
        for (PageEditOutput output : outputs) {
            if (output.pages().size() > limits.maxPagesPerOutput()) {
                throw new InvalidInputException("pageEdit.limit.pages",
                        "At most " + limits.maxPagesPerOutput() + " pages per new document");
            }
            for (PageRef page : output.pages()) {
                int available = page.fromScan() ? scanPages : pageCounts.getOrDefault(page.document(), 0);
                if (page.fromScan() && scanPages == 0) {
                    throw new InvalidInputException("pageEdit.scan.missing", "The plan uses new pages but none came");
                }
                if (page.pageIndex() >= available) {
                    throw new InvalidInputException("pageEdit.page.invalid", "Page " + (page.pageIndex() + 1)
                            + " does not exist in its source");
                }
            }
        }
    }
}
