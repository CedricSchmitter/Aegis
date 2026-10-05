package dev.aegis.bootstrap.config;

import dev.aegis.contracts.application.ContractPresenter;
import dev.aegis.contracts.application.ContractQueryService;
import dev.aegis.contracts.application.ContractService;
import dev.aegis.contracts.application.api.ContractCommands;
import dev.aegis.contracts.application.api.ContractDeadlineQuery;
import dev.aegis.contracts.application.api.ContractExportQuery;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.contracts.application.api.ImportContract;
import dev.aegis.contracts.application.api.RecordContractObservation;
import dev.aegis.contracts.application.api.ContractReconciliationScheduler;
import dev.aegis.contracts.application.ports.ContractEvents;
import dev.aegis.contracts.domain.ContractRepository;
import dev.aegis.documents.application.AnalysisSettings;
import dev.aegis.documents.application.AnalysisTrigger;
import dev.aegis.documents.application.AnalysisViews;
import dev.aegis.documents.adapters.jobs.ArchiveTextCheckTask;
import dev.aegis.documents.adapters.jobs.ReconciliationTask;
import dev.aegis.documents.application.ArchivalService;
import dev.aegis.documents.application.api.ImportDocumentAnalysis;
import dev.aegis.documents.domain.analysis.DocumentAnalysisRepository;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.ArchivalUseCases;
import dev.aegis.documents.application.ArchiveTextService;
import dev.aegis.documents.application.DocumentCaptureService;
import dev.aegis.documents.application.DocumentCommandService;
import dev.aegis.documents.application.DocumentPresenter;
import dev.aegis.documents.application.CatalogImportService;
import dev.aegis.documents.application.CatalogQueryService;
import dev.aegis.documents.application.CategoryService;
import dev.aegis.documents.application.ClassificationService;
import dev.aegis.documents.application.PlacementService;
import dev.aegis.documents.application.ClassificationViews;
import dev.aegis.documents.application.DocumentQueryService;
import dev.aegis.documents.application.DocumentTextService;
import dev.aegis.documents.application.DocumentTextExtractor;
import dev.aegis.documents.application.ports.PassageIndex;
import dev.aegis.documents.adapters.jobs.PassageIndexTask;
import dev.aegis.documents.application.LabelService;
import dev.aegis.documents.application.DocumentRegistrar;
import dev.aegis.documents.application.HouseholdSubjectMigrationService;
import dev.aegis.documents.application.PurgeUseCases;
import dev.aegis.documents.application.DerivationPortabilityService;
import dev.aegis.documents.application.PageEditRunner;
import dev.aegis.documents.application.PageEditService;
import dev.aegis.documents.application.PageEditSettings;
import dev.aegis.documents.application.PageEditStepService;
import dev.aegis.documents.application.PageEditSteps;
import dev.aegis.documents.application.PageQueryService;
import dev.aegis.documents.application.PageSources;
import dev.aegis.documents.application.api.DerivationPortability;
import dev.aegis.documents.application.api.PageEditCommands;
import dev.aegis.documents.application.api.PageQueries;
import dev.aegis.documents.application.ports.PageCache;
import dev.aegis.documents.application.ports.PdfPages;
import dev.aegis.documents.application.ports.PdfText;
import dev.aegis.documents.application.ports.ScanStore;
import dev.aegis.documents.domain.pages.DerivationRepository;
import dev.aegis.documents.domain.pages.PageEditPlan;
import dev.aegis.documents.domain.pages.PageEditRepository;
import dev.aegis.documents.application.DocumentPurgeService;
import dev.aegis.documents.application.ReconciliationService;
import dev.aegis.documents.application.RegisterReceivedDocument;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.PlacementCommands;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.documents.application.api.ImportCatalog;
import dev.aegis.documents.application.api.ImportDocument;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.api.MigrateHouseholdSubjects;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.application.ports.HouseholdPersons;
import dev.aegis.documents.application.ports.LegacyHouseholdSubjects;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.documents.domain.placement.PlacementSuggestionRepository;
import dev.aegis.documents.domain.text.ArchiveTextCheckRepository;
import dev.aegis.identity.application.HouseholdAccessPolicy;
import dev.aegis.identity.application.MemberService;
import dev.aegis.identity.application.api.DesignateAssistants;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberPreferences;
import dev.aegis.identity.application.api.MemberSummary;
import dev.aegis.identity.application.api.ProvisionMembers;
import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.identity.domain.PasswordHasher;
import dev.aegis.notifications.application.NoticeService;
import dev.aegis.notifications.adapters.push.PushDispatchTask;
import dev.aegis.notifications.application.api.PushDispatch;
import dev.aegis.notifications.application.api.PushSettings;
import dev.aegis.notifications.application.push.PushDispatcher;
import dev.aegis.notifications.application.push.PushLedger;
import dev.aegis.notifications.application.push.PushSender;
import dev.aegis.notifications.application.push.PushServerKey;
import dev.aegis.notifications.application.push.PushService;
import dev.aegis.notifications.application.push.PushServiceHosts;
import dev.aegis.notifications.application.push.PushTexts;
import dev.aegis.notifications.application.push.RecipientLocales;
import dev.aegis.notifications.domain.push.PushDeliveryLog;
import dev.aegis.notifications.domain.push.PushPreferencesRepository;
import dev.aegis.notifications.domain.push.PushSubscriptionRepository;
import dev.aegis.notifications.application.api.ForgetNotices;
import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.review.adapters.jobs.ReviewReconciliationTask;
import dev.aegis.review.application.ReviewService;
import dev.aegis.review.application.api.ReviewInbox;
import dev.aegis.review.application.api.ForgetReviewCards;
import dev.aegis.review.application.api.ReviewPortability;
import dev.aegis.review.application.api.ReviewReconciliation;
import dev.aegis.review.application.ports.ReconciliationLock;
import dev.aegis.review.application.ports.ReviewActionDispatcher;
import dev.aegis.review.application.ports.ReviewProducer;
import dev.aegis.review.domain.ReviewRepository;
import dev.aegis.operations.adapters.jobs.JobHealthWatchTask;
import dev.aegis.operations.application.IntegrationStatusService;
import dev.aegis.operations.application.JobHealthWatchService;
import dev.aegis.operations.application.StorageHealthService;
import dev.aegis.operations.application.StorageHealthWatchService;
import dev.aegis.operations.application.StorageLimits;
import dev.aegis.operations.application.api.JobHealthWatch;
import dev.aegis.operations.application.api.RecurringJob;
import dev.aegis.operations.application.api.StorageHealthWatch;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.operations.application.ports.BackupStateSource;
import dev.aegis.operations.application.ports.DataStorageSource;
import dev.aegis.operations.application.ports.HealthCheckSource;
import dev.aegis.operations.application.ports.ProcessingOverviewSource;
import dev.aegis.operations.application.ports.JobIncidentStore;
import dev.aegis.operations.application.ports.RecurringJobStateSource;
import dev.aegis.operations.application.ports.StorageIncidentStore;
import dev.aegis.planning.application.TaskCommandService;
import dev.aegis.planning.application.CancellationDeadlineService;
import dev.aegis.planning.application.TaskPresenter;
import dev.aegis.planning.application.TaskQueryService;
import dev.aegis.planning.application.api.ImportTask;
import dev.aegis.planning.application.api.CancellationDeadlineReconciliation;
import dev.aegis.planning.application.api.CancellationReminderPublication;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.ports.CalendarPort;
import dev.aegis.planning.application.ports.CancellationReminderScheduler;
import dev.aegis.planning.application.ports.PlanningEvents;
import dev.aegis.planning.domain.TaskRepository;
import dev.aegis.portability.application.ExportService;
import dev.aegis.portability.application.RestoreService;
import dev.aegis.portability.application.api.CreateExport;
import dev.aegis.portability.application.api.ExportQueries;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.portability.application.ports.ExportSerializer;
import dev.aegis.portability.application.ports.ExportSourceOpener;
import dev.aegis.portability.application.ports.ExportStore;
import dev.aegis.shared.ProductLocale;
import dev.aegis.shared.Actor;
import dev.aegis.shared.HouseholdAccess;
import dev.aegis.shared.Trash;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

/**
 * Explicit wiring of application services. Services that mutate state are exposed through transactional proxies
 * of their use-case interfaces; query services that call external systems are deliberately not transactional.
 * Raw service objects live in holder records so that only the per-interface proxies are injectable.
 */
@Configuration
public class UseCaseConfiguration {

    @Bean
    HouseholdAccess householdAccess(MemberRepository members) {
        return new HouseholdAccessPolicy(members);
    }

    /** Raw service instances are held in records so that only the per-interface proxies are injectable. */
    record IdentityServices(MemberService members) {
    }

    @Bean
    IdentityServices identityServices(MemberRepository members, PasswordHasher hasher, HouseholdAccess access,
            Clock clock) {
        return new IdentityServices(new MemberService(members, hasher, access, clock));
    }

    @Bean
    MemberDirectory memberDirectory(IdentityServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberDirectory.class, services.members(), tm);
    }

    @Bean
    MemberCredentialsQuery memberCredentialsQuery(IdentityServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberCredentialsQuery.class, services.members(), tm);
    }

    @Bean
    ProvisionMembers provisionMembers(IdentityServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ProvisionMembers.class, services.members(), tm);
    }

    @Bean
    MemberPreferences memberPreferences(IdentityServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberPreferences.class, services.members(), tm);
    }

    @Bean
    DesignateAssistants designateAssistants(IdentityServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(DesignateAssistants.class, services.members(), tm);
    }

    /** How long a trashed document, task or contract is guaranteed to stay restorable (ADR-0010). */
    @Bean
    Trash.RecoveryWindow trashRecoveryWindow(@Value("${aegis.trash.recovery-window:30d}") Duration window) {
        return new Trash.RecoveryWindow(window);
    }

    /**
     * The persons a document can be about, read through the identity contract (ADR-0017). The documents context
     * asks as a named household process, which the member directory is open to; the assistant is left out.
     */
    @Bean
    HouseholdPersons householdPersons(MemberDirectory members) {
        Actor process = Actor.householdProcess("document-subjects");
        return () -> members.listMembers(process).stream().filter(member -> member.active() && member.isPerson())
                .map(MemberSummary::id).toList();
    }

    /** Replaces the retired "both" subject once at startup, in one transaction (ADR-0017). */
    @Bean
    MigrateHouseholdSubjects migrateHouseholdSubjects(LegacyHouseholdSubjects legacy, HouseholdPersons persons,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MigrateHouseholdSubjects.class,
                new HouseholdSubjectMigrationService(legacy, persons), tm);
    }

    @Bean
    ArchivalSettings archivalSettings(@Value("${aegis.archival.max-attempts:8}") int maxAttempts,
            @Value("${aegis.archival.poll-timeout:20m}") Duration pollTimeout,
            @Value("${aegis.archival.staging-retention:7d}") Duration stagingRetention,
            @Value("${aegis.archival.max-upload-bytes:52428800}") long maxUploadBytes,
            @Value("${aegis.archival.poll-interval:5s}") Duration pollInterval,
            @Value("${aegis.archival.retry-base:30s}") Duration retryBase) {
        return new ArchivalSettings(maxAttempts, pollTimeout, stagingRetention, maxUploadBytes, pollInterval,
                retryBase);
    }

    @Bean
    ClassificationViews classificationViews(ClassificationRepository classifications, CategoryRepository categories,
            LabelRepository labels, PlacementSuggestionRepository placements) {
        return new ClassificationViews(classifications, categories, labels, placements);
    }

    @Bean
    DocumentPresenter documentPresenter(ProcessingScheduler scheduler, ClassificationViews classifications,
            AnalysisViews analyses, ArchiveTextCheckRepository textChecks) {
        return new DocumentPresenter(scheduler, classifications, analyses, textChecks);
    }

    @Bean
    RegisterReceivedDocument registerReceivedDocument(DocumentRepository documents,
            ClassificationRepository classifications, CategoryRepository categories, CatalogLock lock,
            ProcessingScheduler scheduler, DocumentAnalysisRepository analyses, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RegisterReceivedDocument.class,
                new DocumentRegistrar(documents, classifications, categories, lock, scheduler, analyses), tm);
    }

    record DocumentServices(DocumentCaptureService capture, DocumentQueryService queries) {
    }

    @Bean
    DocumentServices documentServices(OriginalStore store, RegisterReceivedDocument registrar,
            DocumentPresenter presenter, HouseholdAccess access, ArchivalSettings settings, Clock clock,
            DocumentRepository documents, PaperlessArchive archive, DocumentCatalog catalog,
            CategoryRepository categories, LabelRepository labels, ClassificationRepository classifications,
            ClassificationViews views, PlacementSuggestionRepository placements,
            DocumentAnalysisRepository analyses, AnalysisSettings analysisSettings, HouseholdPersons persons) {
        return new DocumentServices(
                new DocumentCaptureService(store, registrar, presenter, categories, persons, access, settings,
                        analysisSettings, clock),
                new DocumentQueryService(documents, archive, store, presenter, catalog, categories, labels,
                        classifications, views, placements, analyses, analysisSettings, access, clock));
    }

    @Bean
    CatalogQueries catalogQueries(CategoryRepository categories, LabelRepository labels, DocumentCatalog catalog,
            HouseholdAccess access, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(CatalogQueries.class,
                new CatalogQueryService(categories, labels, catalog, access), tm);
    }

    @Bean
    CategoryCommands categoryCommands(CategoryRepository categories, LabelRepository labels,
            ClassificationRepository classifications, DocumentRepository documents, DocumentCatalog catalog,
            CatalogLock lock, HouseholdPersons persons, HouseholdAccess access, Clock clock,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(CategoryCommands.class, new CategoryService(categories, labels,
                classifications, documents, catalog, lock, persons, access, clock), tm);
    }

    @Bean
    LabelCommands labelCommands(LabelRepository labels, CategoryRepository categories,
            ClassificationRepository classifications, DocumentCatalog catalog, CatalogLock lock,
            HouseholdAccess access, Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(LabelCommands.class, new LabelService(labels, categories, classifications,
                catalog, lock, access, clock), tm);
    }

    record ClassificationServices(ClassificationService classification) {
    }

    @Bean
    ClassificationServices classificationServices(ClassificationRepository classifications,
            DocumentRepository documents, CategoryRepository categories, LabelRepository labels,
            PlacementSuggestionRepository placements, CatalogLock lock, DocumentPresenter presenter,
            HouseholdAccess access, Clock clock) {
        return new ClassificationServices(new ClassificationService(classifications, documents, categories, labels,
                placements, lock, presenter, access, clock));
    }

    /** Member decisions about proposed folder placements; accepting creates folders and moves. */
    @Bean
    PlacementCommands placementCommands(ClassificationRepository classifications,
            PlacementSuggestionRepository placements, CategoryRepository categories, LabelRepository labels,
            DocumentRepository documents, CatalogLock lock, DocumentPresenter presenter, HouseholdAccess access,
            Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PlacementCommands.class, new PlacementService(classifications, placements,
                categories, labels, documents, lock, presenter, access, clock), tm);
    }

    @Bean
    ClassificationCommands classificationCommands(ClassificationServices services,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ClassificationCommands.class, services.classification(), tm);
    }

    /** Label proposals of automatic classification, used through the document analysis service. */
    @Bean
    ProposeClassification proposeClassification(ClassificationServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ProposeClassification.class, services.classification(), tm);
    }

    @Bean
    ImportCatalog importCatalog(LabelRepository labels, CategoryRepository categories,
            ClassificationRepository classifications, PlacementSuggestionRepository placements,
            DocumentRepository documents, CatalogLock lock, HouseholdAccess access, Clock clock,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportCatalog.class, new CatalogImportService(labels, categories,
                classifications, placements, documents, lock, access, clock), tm);
    }

    @Bean
    UploadDocument uploadDocument(DocumentServices services) {
        return TransactionalProxy.expose(UploadDocument.class, services.capture());
    }

    @Bean
    ImportDocument importDocument(DocumentServices services) {
        return TransactionalProxy.expose(ImportDocument.class, services.capture());
    }

    @Bean
    DocumentQueries documentQueries(DocumentServices services) {
        return TransactionalProxy.expose(DocumentQueries.class, services.queries());
    }

    @Bean
    DocumentTextQuery documentTextQuery(DocumentRepository documents, DocumentTextExtractor extractor, HouseholdAccess access) {
        return TransactionalProxy.expose(DocumentTextQuery.class,
                new DocumentTextService(documents, extractor, access));
    }

    @Bean
    DocumentReferenceQuery documentReferenceQuery(DocumentServices services) {
        return TransactionalProxy.expose(DocumentReferenceQuery.class, services.queries());
    }

    @Bean
    DocumentExportQuery documentExportQuery(DocumentServices services) {
        return TransactionalProxy.expose(DocumentExportQuery.class, services.queries());
    }

    @Bean
    DocumentStatusQuery documentStatusQuery(DocumentServices services) {
        return TransactionalProxy.expose(DocumentStatusQuery.class, services.queries());
    }

    @Bean
    DocumentCommands documentCommands(DocumentRepository documents, ProcessingScheduler scheduler,
            DocumentPresenter presenter, AnalysisTrigger analysis, HouseholdPersons persons, HouseholdAccess access,
            Trash.RecoveryWindow recoveryWindow, Clock clock, PlatformTransactionManager tm, PassageIndex passages) {
        return TransactionalProxy.wrap(DocumentCommands.class, new DocumentCommandService(documents, scheduler,
                presenter, analysis, persons, access, recoveryWindow, clock, passages), tm);
    }

    @Bean
    ArchivalUseCases archivalUseCases(DocumentRepository documents, DocumentEvents events, AnalysisTrigger analysis,
            ArchivalSettings settings, Clock clock, PlatformTransactionManager tm, PassageIndex passages) {
        return TransactionalProxy.wrap(ArchivalUseCases.class, new ArchivalService(documents, events, analysis,
                settings, clock, passages), tm);
    }

    /** Steps of a document's permanent deletion (ADR-0021); each one short transaction around the job's work. */
    @Bean
    PurgeUseCases purgeUseCases(DocumentRepository documents, DocumentEvents events, ArchivalSettings settings,
            Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PurgeUseCases.class, new DocumentPurgeService(documents, events, settings,
                clock), tm);
    }

    /** Bounds of the page editor (ADR-0024); every new document has to meet the upload limit. */
    @Bean
    PageEditSettings pageEditSettings(@Value("${aegis.documents.page-edit.max-outputs:10}") int maxOutputs,
            @Value("${aegis.documents.page-edit.max-pages-per-output:200}") int maxPagesPerOutput,
            @Value("${aegis.documents.page-edit.max-sources:20}") int maxSources,
            @Value("${aegis.documents.page-edit.max-new-pages:30}") int maxNewPages,
            @Value("${aegis.documents.page-edit.max-attempts:5}") int maxAttempts,
            @Value("${aegis.documents.page-edit.preview-long-side:480}") int previewLongSide,
            ArchivalSettings archival) {
        return new PageEditSettings(new PageEditPlan.Limits(maxOutputs, maxPagesPerOutput, maxSources, maxNewPages),
                archival.maxUploadBytes(), maxAttempts, previewLongSide);
    }

    @Bean
    PageSources pageSources(OriginalStore store, PaperlessArchive archive, PageCache cache, PdfPages pdf) {
        return new PageSources(store, archive, cache, pdf);
    }

    /** Transactional steps of the page editor; registering an output joins the capture registration's transaction. */
    @Bean
    PageEditSteps pageEditSteps(PageEditRepository edits, DerivationRepository derivations,
            RegisterReceivedDocument registrar, ProcessingScheduler scheduler, PageEditSettings settings, Clock clock,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PageEditSteps.class, new PageEditStepService(edits, derivations, registrar,
                scheduler, settings, clock), tm);
    }

    /** Requests are not one transaction: the scan is stored and sources are read before the operation commits. */
    @Bean
    PageEditCommands pageEditCommands(PageEditSteps steps, PageEditRepository edits, DocumentRepository documents,
            PageSources sources, ScanStore scans, PdfPages pdf, CategoryRepository categories,
            HouseholdPersons persons, HouseholdAccess access, PageEditSettings settings, Clock clock) {
        return TransactionalProxy.expose(PageEditCommands.class, new PageEditService(steps, edits, documents, sources,
                scans, pdf, categories, persons, access, settings, clock));
    }

    @Bean
    PageQueries pageQueries(DocumentRepository documents, DerivationRepository derivations, PageSources sources,
            PageCache cache, PdfPages pdf, HouseholdAccess access, PageEditSettings settings) {
        return TransactionalProxy.expose(PageQueries.class, new PageQueryService(documents, derivations, sources,
                cache, pdf, access, settings));
    }

    /** Derivations in the portable export; each restored derivation is one transaction (ADR-0024). */
    @Bean
    DerivationPortability derivationPortability(DocumentRepository documents, DerivationRepository derivations,
            HouseholdAccess access, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(DerivationPortability.class,
                new DerivationPortabilityService(documents, derivations, access), tm);
    }

    @Bean
    PageEditRunner pageEditRunner(PageEditSteps steps, DocumentRepository documents, PageSources sources,
            ScanStore scans, PdfPages pdf, OriginalStore store, PageCache cache, PageEditSettings settings,
            AnalysisSettings analysis, Clock clock) {
        return new PageEditRunner(steps, documents, sources, scans, pdf, store, cache, settings, analysis, clock);
    }

    @Bean
    ReconciliationService reconciliationService(DocumentRepository documents, OriginalStore store,
            PaperlessArchive archive, ProcessingScheduler scheduler, ArchivalSettings settings, Clock clock) {
        return new ReconciliationService(documents, store, archive, scheduler, settings, clock);
    }

    /** Whether the archive recognized text (ADR-0012); each check is one write of derived data, no transaction. */
    @Bean
    ArchiveTextService archiveTextService(DocumentRepository documents, ArchiveTextCheckRepository checks,
            PaperlessArchive archive, @Value("${aegis.documents.text-check.batch-size:10}") int batchSize,
            Clock clock) {
        return new ArchiveTextService(documents, checks, archive, batchSize, clock);
    }

    @Bean
    ContractPresenter contractPresenter(DocumentReferenceQuery documents) {
        return new ContractPresenter(documents);
    }

    record ContractServices(ContractService commands, ContractQueryService queries) {
    }

    @Bean
    ContractServices contractServices(ContractRepository contracts, DocumentReferenceQuery documents,
            ContractPresenter presenter, HouseholdAccess access, Clock clock,
            ContractReconciliationScheduler reconciliation, Trash.RecoveryWindow recoveryWindow,
            ContractEvents events) {
        return new ContractServices(new ContractService(contracts, documents, presenter, access, clock,
                reconciliation, recoveryWindow, events),
                new ContractQueryService(contracts, presenter, access, clock));
    }

    @Bean
    ContractCommands contractCommands(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ContractCommands.class, services.commands(), tm);
    }

    /** Proposed facts from an extraction run; they are stored for review, never applied. */
    @Bean
    RecordContractObservation recordContractObservation(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RecordContractObservation.class, services.commands(), tm);
    }

    @Bean
    ImportContract importContract(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportContract.class, services.commands(), tm);
    }

    @Bean
    ContractQueries contractQueries(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ContractQueries.class, services.queries(), tm);
    }

    /** Public contract for confirmed cancellation deadlines, consumed by planning. */
    @Bean
    ContractDeadlineQuery contractDeadlineQuery(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ContractDeadlineQuery.class, services.queries(), tm);
    }

    @Bean
    ContractExportQuery contractExportQuery(ContractServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ContractExportQuery.class, services.queries(), tm);
    }

    @Bean
    TaskPresenter taskPresenter(DocumentReferenceQuery documents) {
        return new TaskPresenter(documents);
    }

    record PlanningServices(TaskCommandService commands, TaskQueryService queries) {
    }

    @Bean
    PlanningServices planningServices(TaskRepository tasks, DocumentReferenceQuery documents,
            PlanningEvents events, TaskPresenter presenter, HouseholdAccess access, Clock clock,
            CalendarPort calendar, CancellationDeadlineReconciliation cancellations,
            Trash.RecoveryWindow recoveryWindow) {
        return new PlanningServices(new TaskCommandService(tasks, documents, events, presenter, access,
                cancellations, recoveryWindow, clock), new TaskQueryService(tasks, calendar, presenter, access,
                clock));
    }

    @Bean
    TaskCommands taskCommands(PlanningServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(TaskCommands.class, services.commands(), tm);
    }

    @Bean
    ImportTask importTask(PlanningServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportTask.class, services.commands(), tm);
    }

    @Bean
    TaskQueries taskQueries(PlanningServices services) {
        return TransactionalProxy.expose(TaskQueries.class, services.queries());
    }

    @Bean
    TaskExportQuery taskExportQuery(PlanningServices services) {
        return TransactionalProxy.expose(TaskExportQuery.class, services.queries());
    }

    record CancellationServices(CancellationDeadlineService service) {
    }

    @Bean
    CancellationServices cancellationServices(ContractDeadlineQuery contracts, TaskRepository tasks,
            CancellationReminderScheduler reminders, MemberDirectory members, PublishNotice notices,
            PlanningEvents events, HouseholdAccess access, Clock clock) {
        return new CancellationServices(new CancellationDeadlineService(contracts, tasks, reminders, members,
                notices, events, access, clock));
    }

    @Bean
    CancellationDeadlineReconciliation cancellationDeadlineReconciliation(CancellationServices services,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(CancellationDeadlineReconciliation.class, services.service(), tm);
    }

    @Bean
    CancellationReminderPublication cancellationReminderPublication(CancellationServices services,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(CancellationReminderPublication.class, services.service(), tm);
    }

    record NotificationServices(NoticeService notices) {
    }

    @Bean
    NotificationServices notificationServices(NoticeRepository notices, HouseholdAccess access, Clock clock) {
        return new NotificationServices(new NoticeService(notices, access, clock));
    }

    @Bean
    PublishNotice publishNotice(NotificationServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PublishNotice.class, services.notices(), tm);
    }

    @Bean
    NoticeInbox noticeInbox(NotificationServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(NoticeInbox.class, services.notices(), tm);
    }

    @Bean
    ForgetNotices forgetNotices(NotificationServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ForgetNotices.class, services.notices(), tm);
    }

    record PushServices(PushService service) {
    }

    /** Web Push to the members' devices (issue #28); sending runs outside transactions between ledger steps. */
    @Bean
    PushServices pushServices(PushSubscriptionRepository subscriptions, PushPreferencesRepository preferences,
            PushDeliveryLog deliveries, NoticeRepository notices, PushServerKey serverKey, PushTexts texts,
            MemberDirectory members, HouseholdAccess access, Clock clock,
            @Value("${aegis.push.catch-up-window:24h}") Duration catchUpWindow,
            @Value("${aegis.push.allowed-hosts:}") List<String> allowedHosts,
            @Value("${aegis.household.zone:Europe/Berlin}") String zone) {
        RecipientLocales locales = member -> members.findMember(member).map(MemberSummary::locale)
                .orElse(ProductLocale.defaultLocale());
        PushServiceHosts hosts = allowedHosts.isEmpty() ? PushServiceHosts.DEFAULT : new PushServiceHosts(allowedHosts);
        return new PushServices(new PushService(subscriptions, preferences, deliveries, notices, serverKey, texts,
                locales, hosts, access, catchUpWindow, ZoneId.of(zone), clock));
    }

    @Bean
    PushSettings pushSettings(PushServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PushSettings.class, services.service(), tm);
    }

    @Bean
    PushDispatch pushDispatch(PushServices services, PushSender sender, PlatformTransactionManager tm) {
        return new PushDispatcher(TransactionalProxy.wrap(PushLedger.class, services.service(), tm), sender);
    }

    record ReviewServices(ReviewService service) {
    }

    @Bean
    ReviewServices reviewServices(ReviewRepository items, List<ReviewProducer> producers,
            ReviewActionDispatcher actions, ReconciliationLock lock, HouseholdAccess access, MemberDirectory members,
            PublishNotice notices, Clock clock) {
        return new ReviewServices(new ReviewService(items, producers, actions, lock, access, members, notices, clock));
    }

    @Bean
    ReviewInbox reviewInbox(ReviewServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ReviewInbox.class, services.service(), tm);
    }

    /** Background reconciliation: one transaction per run, so a card and its notices commit together. */
    @Bean
    ReviewReconciliation reviewReconciliation(ReviewServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ReviewReconciliation.class, services.service(), tm);
    }

    @Bean
    ReviewPortability reviewPortability(ReviewServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ReviewPortability.class, services.service(), tm);
    }

    @Bean
    ForgetReviewCards forgetReviewCards(ReviewServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ForgetReviewCards.class, services.service(), tm);
    }

    /**
     * Each recurring job the status page reports (ADR-0013) is declared with the interval it runs at: the
     * household jobs and the watch over them.
     */
    @Bean
    SystemStatusQuery systemStatusQuery(List<HealthCheckSource> sources, ProcessingOverviewSource processing,
            ReviewReconciliationTask reviewReconciliation, PushDispatchTask pushDispatch, JobHealthWatchTask watchTask,
            RecurringJobStateSource jobStates, StorageHealthService storage, HouseholdAccess access, Clock clock, PassageIndexTask passages) {
        List<RecurringJob> recurringJobs = new ArrayList<>(householdJobs(reviewReconciliation, pushDispatch, passages));
        recurringJobs.add(new RecurringJob(JobHealthWatchTask.NAME, watchTask.interval()));
        return new IntegrationStatusService(sources, processing, recurringJobs, jobStates, storage, access, clock);
    }

    /** The external backup and free disk space (ADR-0015); the owner chose 10 % and 5 GB. */
    @Bean
    StorageHealthService storageHealthService(BackupStateSource backupStates, DataStorageSource dataStorage,
            @Value("${aegis.storage.low-free-fraction:0.10}") double lowFreeFraction,
            @Value("${aegis.storage.low-free-space:5GB}") DataSize lowFreeSpace, Clock clock) {
        return new StorageHealthService(backupStates, dataStorage,
                new StorageLimits(lowFreeFraction, lowFreeSpace.toBytes()), clock);
    }

    /**
     * Members are told when the external backup stops working or a disk runs low (ADR-0015). Like the job watch it
     * runs without a surrounding transaction because each step is idempotent.
     */
    @Bean
    StorageHealthWatch storageHealthWatch(StorageHealthService storage, StorageIncidentStore incidents,
            MemberDirectory members, PublishNotice notices, Clock clock) {
        return new StorageHealthWatchService(storage, incidents, members, notices, clock);
    }

    /**
     * Members are told when a household job stops running (ADR-0014); the watch does not watch itself. It
     * runs without a surrounding transaction: each step is idempotent, and one job's failure must not roll back the
     * others.
     */
    @Bean
    JobHealthWatch jobHealthWatch(ReviewReconciliationTask reviewReconciliation, PushDispatchTask pushDispatch,
            RecurringJobStateSource jobStates, JobIncidentStore incidents, MemberDirectory members,
            PublishNotice notices, Clock clock, PassageIndexTask passages) {
        return new JobHealthWatchService(householdJobs(reviewReconciliation, pushDispatch, passages), jobStates, incidents,
                members, notices, clock);
    }

    private static List<RecurringJob> householdJobs(ReviewReconciliationTask reviewReconciliation,
            PushDispatchTask pushDispatch, PassageIndexTask passages) {
        return List.of(new RecurringJob(ReviewReconciliationTask.NAME, reviewReconciliation.interval()),
                new RecurringJob(ArchiveTextCheckTask.NAME, ArchiveTextCheckTask.INTERVAL),
                new RecurringJob(PassageIndexTask.NAME, passages.interval()),
                new RecurringJob(ReconciliationTask.NAME, ReconciliationTask.INTERVAL),
                new RecurringJob(PushDispatchTask.NAME, pushDispatch.interval()));
    }

    record PortabilityServices(ExportService exports) {
    }

    @Bean
    PortabilityServices portabilityServices(DocumentExportQuery documents, DerivationPortability derivations,
            TaskExportQuery tasks, ContractExportQuery contracts, ReviewPortability reviews, MemberDirectory members,
            ExportStore store, ExportSerializer serializer, HouseholdAccess access, Clock clock,
            @Value("${aegis.build.commit:dev}") String commit) {
        return new PortabilityServices(new ExportService(documents, derivations, tasks, contracts, reviews, members,
                store, serializer, access, clock, "aegis@" + commit));
    }

    @Bean
    CreateExport createExport(PortabilityServices services) {
        return TransactionalProxy.expose(CreateExport.class, services.exports());
    }

    @Bean
    ExportQueries exportQueries(PortabilityServices services) {
        return TransactionalProxy.expose(ExportQueries.class, services.exports());
    }

    @Bean
    RestoreExport restoreExport(ExportSourceOpener opener, ExportSerializer serializer, MemberDirectory members,
            ProvisionMembers provisioning, ImportDocument importDocument, ImportCatalog importCatalog,
            ImportDocumentAnalysis importAnalysis, ImportTask importTask, ImportContract importContract,
            ReviewPortability reviews, DerivationPortability derivations, HouseholdAccess access) {
        return new RestoreService(opener, serializer, members, provisioning, importDocument, importCatalog,
                importAnalysis, importTask, importContract, reviews, derivations, access);
    }
}
