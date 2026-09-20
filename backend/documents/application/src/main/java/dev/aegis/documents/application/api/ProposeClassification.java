package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for automatic classifiers (issue #23, #67). Proposals quote the classification version that was
 * analyzed; member decisions are never overridden, retired labels are skipped, and {@code apply} is honored only
 * where the document has no competing document type.
 *
 * <p>Labels and the folder placement of one run are proposed together, in one transaction on one classification
 * version. The placement is resolved against the current folder tree here, not by the classifier: an existing
 * folder wins by id, then by normalized sibling name, so a folder created while the run was working is reused
 * instead of a near-duplicate (ADR-0005).
 */
public interface ProposeClassification {

    record LabelProposal(UUID labelId, Double confidence, boolean apply) {
    }

    /**
     * A folder placement proposed by a run. {@code folderId} names an existing folder; {@code newPath} is a path
     * below {@code anchorFolderId} ({@code null} = top level) whose missing folders are never created
     * automatically. {@code doubtful} and {@code evidenced} come from the classification policy checks on the text.
     */
    record PlacementProposal(UUID folderId, UUID anchorFolderId, List<String> newPath, Double confidence,
            String evidence, boolean evidenced, boolean doubtful, int generation, String model) {
        public PlacementProposal {
            newPath = newPath == null ? List.of() : List.copyOf(newPath);
        }
    }

    record ProposalCommand(DocumentId documentId, long analyzedVersion, String sourceRef,
            List<LabelProposal> proposals, PlacementProposal placement) {
    }

    /**
     * What the proposal changed. {@code placement} is {@code null} when the run proposed no usable folder;
     * otherwise it is applied ({@code movedTo} set) or left for a member to review.
     */
    record ProposalOutcome(int applied, int suggested, int skipped, long version, PlacementResult placement) {
    }

    /**
     * The resolved placement: {@code folderId} an existing folder, otherwise {@code newPath} below
     * {@code anchorFolderId}. {@code movedTo} is set when the document was filed automatically.
     */
    record PlacementResult(UUID folderId, UUID anchorFolderId, List<String> newPath, UUID movedTo, String key,
            Double confidence, String evidence) {
        public PlacementResult {
            newPath = newPath == null ? List.of() : List.copyOf(newPath);
        }

        public boolean applied() {
            return movedTo != null;
        }
    }

    ProposalOutcome propose(Actor actor, ProposalCommand command);
}
