package dev.aegis.documents.application.api;

import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for automatic classifiers (issue #23). Proposals quote the classification version that was analyzed;
 * member decisions are never overridden, retired labels are skipped, and {@code apply} is honored only where the
 * document has no competing document type.
 */
public interface ProposeClassification {

    record LabelProposal(UUID labelId, Double confidence, boolean apply) {
    }

    record ProposalCommand(DocumentId documentId, long analyzedVersion, String sourceRef,
            List<LabelProposal> proposals) {
    }

    record ProposalOutcome(int applied, int suggested, int skipped, long version) {
    }

    ProposalOutcome propose(Actor actor, ProposalCommand command);
}
