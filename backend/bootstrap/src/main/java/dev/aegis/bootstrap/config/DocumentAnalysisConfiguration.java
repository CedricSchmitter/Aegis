package dev.aegis.bootstrap.config;

import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.assistant.application.api.AiRunStatusQuery;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RequestOutcome;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.api.WorkloadEstimate;
import dev.aegis.assistant.application.classification.DocumentClassificationHandler;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.documents.application.AnalysisSettings;
import dev.aegis.documents.application.AnalysisTrigger;
import dev.aegis.documents.application.AnalysisViews;
import dev.aegis.documents.application.BulkAnalysisDriver;
import dev.aegis.documents.application.BulkAnalysisService;
import dev.aegis.documents.application.BulkAnalysisSettings;
import dev.aegis.documents.application.ClassificationViews;
import dev.aegis.documents.application.DocumentAnalysisService;
import dev.aegis.documents.application.DocumentPresenter;
import dev.aegis.documents.application.api.AutomaticClassification;
import dev.aegis.documents.application.api.BulkAnalysisCommands;
import dev.aegis.documents.application.api.BulkAnalysisQueries;
import dev.aegis.documents.application.api.DocumentAnalysisCommands;
import dev.aegis.documents.application.api.ImportDocumentAnalysis;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.bulk.BulkAnalysisRepository;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.naming.BuiltinLabelNames;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the automatic document analysis (ADR-0004): the documents-side use cases, the bridge from the
 * documents port {@link AnalysisRuns} to the AI execution boundary, and the classification workload handler.
 *
 * <p>The bridge resolves the assistant lazily: the classification handler depends on the documents contract, and the
 * AI run service depends on all handlers, so eager wiring would form a cycle.
 */
@Configuration
public class DocumentAnalysisConfiguration {

    @Bean
    AnalysisSettings analysisSettings(@Value("${aegis.documents.analysis.automatic:true}") boolean automatic,
            @Value("${aegis.documents.analysis.filename-language:DE}") String language,
            @Value("${aegis.documents.analysis.max-text-chars:20000}") int maxTextChars,
            @Value("${aegis.documents.analysis.max-catalog-folders:200}") int maxCatalogFolders,
            @Value("${aegis.household.zone:Europe/Berlin}") String zone) {
        return new AnalysisSettings(automatic, BuiltinLabelNames.Language.parse(language), maxTextChars,
                maxCatalogFolders, ZoneId.of(zone));
    }

    @Bean
    AnalysisRuns analysisRuns(ObjectProvider<RequestAiRun> requests, ObjectProvider<AiRunStatusQuery> statuses,
            ObjectProvider<AiRunQueries> queries, ObjectProvider<AiRunCommands> commands) {
        return new AnalysisRuns() {
            @Override
            public Outcome request(Actor requester, DocumentId id, int generation, long classificationVersion) {
                RunRequest request = new RunRequest(Workload.DOCUMENT_CLASSIFICATION.name(),
                        AnalysisRuns.key(id, generation),
                        List.of(new SourceVersion(SOURCE_CLASSIFICATION, id.toString(), classificationVersion),
                                new SourceVersion(SOURCE_ANALYSIS, id.toString(), generation)),
                        Map.of(), false);
                return switch (requests.getObject().tryRequest(requester, request)) {
                    case RequestOutcome.Requested requested -> new Requested(requested.run().id());
                    case RequestOutcome.Refused refused -> new Unavailable(refused.code());
                };
            }

            @Override
            public Map<String, RunStatus> latest(Collection<String> keys) {
                Map<String, RunStatus> result = new LinkedHashMap<>();
                statuses.getObject().latestByKeys(keys).forEach((key, run) -> result.put(key, status(run)));
                return result;
            }

            @Override
            public CostEstimate estimate(Actor requester) {
                WorkloadEstimate estimate = queries.getObject().estimate(requester,
                        Workload.DOCUMENT_CLASSIFICATION.name());
                return new CostEstimate(estimate.refusal(), estimate.averageCost(), estimate.samples(),
                        estimate.runBudget(), estimate.dailyCostLimit(), estimate.spentToday(), estimate.currency(),
                        estimate.nextBudgetDay());
            }

            @Override
            public void cancel(Actor actor, UUID runId) {
                commands.getObject().cancel(actor, runId);
            }
        };
    }

    private static AnalysisRuns.RunStatus status(RunView run) {
        return new AnalysisRuns.RunStatus(run.id(), run.state(), run.failureCategory(), run.failureCode(),
                run.nextAttemptAt(), run.finishedAt());
    }

    @Bean
    AnalysisViews analysisViews(DocumentAnalysisRepository analyses, AnalysisRuns runs, AnalysisSettings settings) {
        return new AnalysisViews(analyses, runs, settings);
    }

    /** Raw service; only the per-interface proxies below are injectable. */
    record AnalysisServices(DocumentAnalysisService analysis) {
    }

    @Bean
    AnalysisServices analysisServices(DocumentRepository documents, DocumentAnalysisRepository analyses,
            ClassificationRepository classifications, LabelRepository labels, CategoryRepository categories,
            DocumentCatalog catalog, UseCaseConfiguration.ClassificationServices classification,
            LabelCommands labelCommands, CatalogLock lock, PaperlessArchive archive, AnalysisRuns runs,
            ClassificationViews classificationViews, DocumentPresenter presenter, HouseholdPersons persons,
            HouseholdAccess access, AnalysisSettings settings, Clock clock) {
        return new AnalysisServices(new DocumentAnalysisService(documents, analyses, classifications, labels,
                categories, catalog, classification.classification(), labelCommands, lock, archive, runs,
                classificationViews, presenter, persons, access, settings, clock));
    }

    @Bean
    DocumentAnalysisCommands documentAnalysisCommands(AnalysisServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(DocumentAnalysisCommands.class, services.analysis(), tm);
    }

    @Bean
    ImportDocumentAnalysis importDocumentAnalysis(AnalysisServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportDocumentAnalysis.class, services.analysis(), tm);
    }

    /**
     * Not transactional: {@code input} and {@code assess} read the archive and must not hold a transaction, while
     * {@code current} and {@code apply} are only called inside the AI run's completion transaction.
     */
    @Bean
    AutomaticClassification automaticClassification(AnalysisServices services) {
        return TransactionalProxy.expose(AutomaticClassification.class, services.analysis());
    }

    /** Called inside the archival transaction. */
    @Bean
    AnalysisTrigger analysisTrigger(AnalysisServices services) {
        return TransactionalProxy.expose(AnalysisTrigger.class, services.analysis());
    }

    @Bean
    BulkAnalysisSettings bulkAnalysisSettings(
            @Value("${aegis.documents.analysis.bulk.max-documents:500}") int maxDocuments,
            @Value("${aegis.documents.analysis.bulk.batch-size:3}") int batchSize,
            @Value("${aegis.documents.analysis.bulk.poll-interval:10s}") Duration pollInterval,
            @Value("${aegis.documents.analysis.bulk.max-wait:15m}") Duration maxWait) {
        return new BulkAnalysisSettings(maxDocuments, batchSize, pollInterval, maxWait);
    }

    /** Raw service; only the per-interface proxies below are injectable. */
    record BulkServices(BulkAnalysisService bulk) {
    }

    @Bean
    BulkServices bulkServices(BulkAnalysisRepository bulks, DocumentRepository documents,
            DocumentAnalysisRepository analyses, DocumentCatalog catalog, CategoryRepository categories,
            LabelRepository labels, ClassificationViews classificationViews, AnalysisViews analysisViews,
            AnalysisRuns runs, AnalysisServices analysis, ProcessingScheduler scheduler, HouseholdAccess access,
            BulkAnalysisSettings settings, Clock clock) {
        // The per-document requests join the bulk run's transaction, so the raw analysis service is used.
        return new BulkServices(new BulkAnalysisService(bulks, documents, analyses, catalog, categories, labels,
                classificationViews, analysisViews, runs, analysis.analysis(), scheduler, access, settings, clock));
    }

    @Bean
    BulkAnalysisCommands bulkAnalysisCommands(BulkServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(BulkAnalysisCommands.class, services.bulk(), tm);
    }

    @Bean
    BulkAnalysisQueries bulkAnalysisQueries(BulkServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(BulkAnalysisQueries.class, services.bulk(), tm);
    }

    /** One job step per transaction. */
    @Bean
    BulkAnalysisDriver bulkAnalysisDriver(BulkServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(BulkAnalysisDriver.class, services.bulk(), tm);
    }

    @Bean
    WorkloadHandler documentClassificationHandler(AutomaticClassification documents, MemberDirectory members) {
        return new DocumentClassificationHandler(documents, members);
    }
}
