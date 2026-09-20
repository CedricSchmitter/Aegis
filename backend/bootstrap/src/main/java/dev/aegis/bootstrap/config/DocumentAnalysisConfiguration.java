package dev.aegis.bootstrap.config;

import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.application.api.AiRunStatusQuery;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.api.RequestOutcome;
import dev.aegis.assistant.application.api.RunRequest;
import dev.aegis.assistant.application.api.RunView;
import dev.aegis.assistant.application.api.SourceVersion;
import dev.aegis.assistant.application.classification.DocumentClassificationHandler;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.documents.application.AnalysisSettings;
import dev.aegis.documents.application.AnalysisTrigger;
import dev.aegis.documents.application.AnalysisViews;
import dev.aegis.documents.application.ClassificationViews;
import dev.aegis.documents.application.DocumentAnalysisService;
import dev.aegis.documents.application.DocumentPresenter;
import dev.aegis.documents.application.api.AutomaticClassification;
import dev.aegis.documents.application.api.DocumentAnalysisCommands;
import dev.aegis.documents.application.api.ImportDocumentAnalysis;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.ports.AnalysisRuns;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.naming.BuiltinLabelNames;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.shared.Actor;
import dev.aegis.shared.DocumentId;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the automatic document analysis (issue #23, ADR-0004): the documents-side use cases, the bridge from the
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
    AnalysisRuns analysisRuns(ObjectProvider<RequestAiRun> requests, ObjectProvider<AiRunStatusQuery> statuses) {
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
            ClassificationViews classificationViews, DocumentPresenter presenter, HouseholdAccess access,
            AnalysisSettings settings, Clock clock) {
        return new AnalysisServices(new DocumentAnalysisService(documents, analyses, classifications, labels,
                categories, catalog, classification.classification(), labelCommands, lock, archive, runs,
                classificationViews, presenter, access, settings, clock));
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
    WorkloadHandler documentClassificationHandler(AutomaticClassification documents, MemberDirectory members) {
        return new DocumentClassificationHandler(documents, members);
    }
}
