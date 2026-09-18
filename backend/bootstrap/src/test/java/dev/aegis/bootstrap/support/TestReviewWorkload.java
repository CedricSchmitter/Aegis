package dev.aegis.bootstrap.support;

import dev.aegis.assistant.adapters.tools.DocumentSearchTool;
import dev.aegis.assistant.application.InvalidModelOutputException;
import dev.aegis.assistant.application.JsonText;
import dev.aegis.assistant.application.PreparedRun;
import dev.aegis.assistant.application.RunContext;
import dev.aegis.assistant.application.UntrustedContent;
import dev.aegis.assistant.application.ValidatedOutput;
import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.application.ports.ModelMessage;
import dev.aegis.assistant.application.ports.ModelTurn;
import dev.aegis.assistant.domain.SourceRef;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.shared.DocumentId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Integration-test workload for the generic AI execution boundary. It uses {@code DOCUMENT_REVIEW}, which has no
 * production handler yet: it reads the document through the documents contract, lets the model use the real
 * {@code search_documents} tool and applies a suggestion through the real {@link ProposeClassification} use case, so
 * restart, cancellation, tool and outage tests exercise the effect path against PostgreSQL.
 */
public class TestReviewWorkload implements WorkloadHandler {
    private final DocumentQueries documents;
    private final ProposeClassification proposals;

    public TestReviewWorkload(DocumentQueries documents, ProposeClassification proposals) {
        this.documents = documents;
        this.proposals = proposals;
    }

    @Override
    public Workload workload() {
        return Workload.DOCUMENT_REVIEW;
    }

    @Override
    public Set<String> grantedTools() {
        return Set.of(DocumentSearchTool.NAME);
    }

    @Override
    public PreparedRun prepare(RunContext run) {
        SourceRef source = run.sources().getFirst();
        String title = documents.get(run.actor(), DocumentId.of(source.id())).title();
        return new PreparedRun("Suggest one label for the household document.",
                List.of(ModelMessage.user("Document title:\n"
                        + UntrustedContent.wrap("document", source.id(), title, 500))),
                Map.of("type", "object", "properties", Map.of("labelId", Map.of("type", "string")),
                        "required", List.of("labelId"), "additionalProperties", false));
    }

    @Override
    public ValidatedOutput validate(RunContext run, ModelTurn turn) {
        if (turn.structuredOutput() instanceof Map<?, ?> answer && answer.get("labelId") instanceof String id) {
            try {
                return new ValidatedOutput(JsonText.write(answer), UUID.fromString(id));
            } catch (IllegalArgumentException notAnId) {
                // falls through to the rejection
            }
        }
        throw new InvalidModelOutputException("output.schemaMismatch", "labelId must be a label id");
    }

    @Override
    public boolean sourcesCurrent(RunContext run) {
        SourceRef source = run.sources().getFirst();
        return documents.get(run.actor(), DocumentId.of(source.id())).classification().version() == source.version();
    }

    @Override
    public Optional<String> effectKey(RunContext run, ValidatedOutput output) {
        SourceRef source = run.sources().getFirst();
        return Optional.of("it-review:" + source.id() + ":" + source.version());
    }

    @Override
    public void apply(RunContext run, ValidatedOutput output) {
        SourceRef source = run.sources().getFirst();
        proposals.propose(run.actor(), new ProposeClassification.ProposalCommand(DocumentId.of(source.id()),
                source.version(), "ai-run:" + run.id(),
                List.of(new ProposeClassification.LabelProposal((UUID) output.value(), 0.8, false))));
    }
}
