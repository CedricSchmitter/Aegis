package dev.aegis.contracts.application.api;

import dev.aegis.contracts.application.api.ContractViews.ContractSummary;
import dev.aegis.contracts.application.api.ContractViews.ContractView;
import dev.aegis.shared.Actor;
import dev.aegis.shared.ContractId;
import dev.aegis.shared.DocumentId;
import java.util.List;

/** Reading contracts and their facts. Every entry point enforces household membership. */
public interface ContractQueries {

    List<ContractSummary> list(Actor actor);

    ContractView get(Actor actor, ContractId id);

    /** Contracts that have at least one fact from that document, for example on the document page. */
    List<ContractSummary> forDocument(Actor actor, DocumentId documentId);
}
