package dev.aegis.review.adapters.actions;

import dev.aegis.documents.application.api.*; import dev.aegis.review.application.ports.ReviewActionDispatcher; import dev.aegis.review.domain.ReviewItem; import dev.aegis.shared.*; import org.springframework.stereotype.Component;

@Component
public class ApplicationReviewActions implements ReviewActionDispatcher {
 private final DocumentQueries queries; private final DocumentCommands documents; private final DocumentAnalysisCommands analysis;
 public ApplicationReviewActions(DocumentQueries queries,DocumentCommands documents,DocumentAnalysisCommands analysis){this.queries=queries;this.documents=documents;this.analysis=analysis;}
 @Override public void execute(Actor actor,ReviewItem item,String action){if(!"DOCUMENT".equals(item.sourceKind()))throw new InvalidInputException("review.action.invalid","No action adapter for this source");DocumentId id=DocumentId.of(item.sourceId());DocumentSummary current=queries.get(actor,id);switch(action){case "RETRY_ARCHIVAL"->{if(current.version()!=item.sourceVersion())throw new StaleVersionException("document",item.sourceId(),item.sourceVersion(),current.version());documents.retryArchival(actor,id);}case "RETRY_ANALYSIS"->{if(current.analysis().version()!=item.sourceVersion())throw new StaleVersionException("analysis",item.sourceId(),item.sourceVersion(),current.analysis().version());analysis.requestAnalysis(actor,id,current.analysis().version());}default->throw new InvalidInputException("review.action.invalid","Unknown review action");}}
}
