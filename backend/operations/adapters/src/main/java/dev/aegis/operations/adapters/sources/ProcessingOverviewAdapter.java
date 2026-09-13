package dev.aegis.operations.adapters.sources;

import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.operations.application.ports.ProcessingOverviewSource;
import dev.aegis.shared.Actor;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Document processing counts through the documents public contract. */
@Component
public class ProcessingOverviewAdapter implements ProcessingOverviewSource {
    private final DocumentStatusQuery documents;

    public ProcessingOverviewAdapter(DocumentStatusQuery documents) {
        this.documents = documents;
    }

    @Override
    public Map<String, Long> processingCounts(Actor actor) {
        return documents.processingCounts(actor);
    }
}
