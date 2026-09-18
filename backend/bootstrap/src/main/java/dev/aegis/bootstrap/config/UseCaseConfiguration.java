package dev.aegis.bootstrap.config;

import dev.aegis.documents.application.ArchivalService;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.ArchivalUseCases;
import dev.aegis.documents.application.DocumentCaptureService;
import dev.aegis.documents.application.DocumentCommandService;
import dev.aegis.documents.application.DocumentPresenter;
import dev.aegis.documents.application.CatalogImportService;
import dev.aegis.documents.application.CatalogQueryService;
import dev.aegis.documents.application.CategoryService;
import dev.aegis.documents.application.ClassificationService;
import dev.aegis.documents.application.ClassificationViews;
import dev.aegis.documents.application.DocumentQueryService;
import dev.aegis.documents.application.LabelService;
import dev.aegis.documents.application.DocumentRegistrar;
import dev.aegis.documents.application.ReconciliationService;
import dev.aegis.documents.application.RegisterReceivedDocument;
import dev.aegis.documents.application.api.CatalogQueries;
import dev.aegis.documents.application.api.CategoryCommands;
import dev.aegis.documents.application.api.ClassificationCommands;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.documents.application.api.ImportCatalog;
import dev.aegis.documents.application.api.ImportDocument;
import dev.aegis.documents.application.api.LabelCommands;
import dev.aegis.documents.application.api.ProposeClassification;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.ports.CatalogLock;
import dev.aegis.documents.application.ports.DocumentCatalog;
import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.DocumentRepository;
import dev.aegis.documents.domain.catalog.CategoryRepository;
import dev.aegis.documents.domain.catalog.LabelRepository;
import dev.aegis.documents.domain.classification.ClassificationRepository;
import dev.aegis.identity.application.HouseholdAccessPolicy;
import dev.aegis.identity.application.MemberService;
import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.identity.application.api.MemberDirectory;
import dev.aegis.identity.application.api.MemberPreferences;
import dev.aegis.identity.application.api.ProvisionMembers;
import dev.aegis.identity.domain.MemberRepository;
import dev.aegis.identity.domain.PasswordHasher;
import dev.aegis.notifications.application.NoticeService;
import dev.aegis.notifications.application.api.NoticeInbox;
import dev.aegis.notifications.application.api.PublishNotice;
import dev.aegis.notifications.domain.NoticeRepository;
import dev.aegis.operations.application.IntegrationStatusService;
import dev.aegis.operations.application.api.SystemStatusQuery;
import dev.aegis.operations.application.ports.IntegrationStatusSource;
import dev.aegis.operations.application.ports.ProcessingOverviewSource;
import dev.aegis.planning.application.TaskCommandService;
import dev.aegis.planning.application.TaskPresenter;
import dev.aegis.planning.application.TaskQueryService;
import dev.aegis.planning.application.api.ImportTask;
import dev.aegis.planning.application.api.TaskCommands;
import dev.aegis.planning.application.api.TaskExportQuery;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.planning.application.ports.CalendarPort;
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
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit wiring of application services. Services that mutate state are exposed through transactional proxies
 * of their use-case interfaces; query services that call external systems are deliberately not transactional.
 * Raw service objects live in holder records so that only the per-interface proxies are injectable.
 */
@Configuration
public class UseCaseConfiguration {

    // Identity ------------------------------------------------------------------------------------------------

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

    // Documents -----------------------------------------------------------------------------------------------

    @Bean
    ArchivalSettings archivalSettings(@Value("${aegis.archival.max-attempts:8}") int maxAttempts,
            @Value("${aegis.archival.poll-timeout:20m}") Duration pollTimeout,
            @Value("${aegis.archival.staging-retention:7d}") Duration stagingRetention,
            @Value("${aegis.archival.max-upload-bytes:52428800}") long maxUploadBytes) {
        return new ArchivalSettings(maxAttempts, pollTimeout, stagingRetention, maxUploadBytes);
    }

    @Bean
    ClassificationViews classificationViews(ClassificationRepository classifications, CategoryRepository categories,
            LabelRepository labels) {
        return new ClassificationViews(classifications, categories, labels);
    }

    @Bean
    DocumentPresenter documentPresenter(ProcessingScheduler scheduler, ClassificationViews classifications) {
        return new DocumentPresenter(scheduler, classifications);
    }

    @Bean
    RegisterReceivedDocument registerReceivedDocument(DocumentRepository documents,
            ClassificationRepository classifications, CategoryRepository categories, CatalogLock lock,
            ProcessingScheduler scheduler, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RegisterReceivedDocument.class,
                new DocumentRegistrar(documents, classifications, categories, lock, scheduler), tm);
    }

    record DocumentServices(DocumentCaptureService capture, DocumentQueryService queries) {
    }

    @Bean
    DocumentServices documentServices(OriginalStore store, RegisterReceivedDocument registrar,
            DocumentPresenter presenter, HouseholdAccess access, ArchivalSettings settings, Clock clock,
            DocumentRepository documents, PaperlessArchive archive, DocumentCatalog catalog,
            CategoryRepository categories, LabelRepository labels, ClassificationRepository classifications,
            ClassificationViews views) {
        return new DocumentServices(
                new DocumentCaptureService(store, registrar, presenter, categories, access, settings, clock),
                new DocumentQueryService(documents, archive, store, presenter, catalog, categories, labels,
                        classifications, views, access, clock));
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
            CatalogLock lock, HouseholdAccess access, Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(CategoryCommands.class, new CategoryService(categories, labels,
                classifications, documents, catalog, lock, access, clock), tm);
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
            DocumentRepository documents, CategoryRepository categories, LabelRepository labels, CatalogLock lock,
            DocumentPresenter presenter, HouseholdAccess access, Clock clock) {
        return new ClassificationServices(new ClassificationService(classifications, documents, categories, labels,
                lock, presenter, access, clock));
    }

    @Bean
    ClassificationCommands classificationCommands(ClassificationServices services,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ClassificationCommands.class, services.classification(), tm);
    }

    /** Entry point for automatic classification (issue #23); no inbound adapter uses it yet. */
    @Bean
    ProposeClassification proposeClassification(ClassificationServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ProposeClassification.class, services.classification(), tm);
    }

    @Bean
    ImportCatalog importCatalog(LabelRepository labels, CategoryRepository categories,
            ClassificationRepository classifications, DocumentRepository documents, CatalogLock lock,
            HouseholdAccess access, Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportCatalog.class, new CatalogImportService(labels, categories,
                classifications, documents, lock, access, clock), tm);
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
            DocumentPresenter presenter, HouseholdAccess access, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(DocumentCommands.class,
                new DocumentCommandService(documents, scheduler, presenter, access), tm);
    }

    @Bean
    ArchivalUseCases archivalUseCases(DocumentRepository documents, DocumentEvents events,
            ArchivalSettings settings, Clock clock, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ArchivalUseCases.class, new ArchivalService(documents, events, settings,
                clock), tm);
    }

    @Bean
    ReconciliationService reconciliationService(DocumentRepository documents, OriginalStore store,
            PaperlessArchive archive, ProcessingScheduler scheduler, ArchivalSettings settings, Clock clock) {
        return new ReconciliationService(documents, store, archive, scheduler, settings, clock);
    }

    // Planning ------------------------------------------------------------------------------------------------

    @Bean
    TaskPresenter taskPresenter(DocumentReferenceQuery documents) {
        return new TaskPresenter(documents);
    }

    record PlanningServices(TaskCommandService commands, TaskQueryService queries) {
    }

    @Bean
    PlanningServices planningServices(TaskRepository tasks, DocumentReferenceQuery documents,
            PlanningEvents events, TaskPresenter presenter, HouseholdAccess access, Clock clock,
            CalendarPort calendar) {
        return new PlanningServices(new TaskCommandService(tasks, documents, events, presenter, access, clock),
                new TaskQueryService(tasks, calendar, presenter, access, clock));
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

    // Notifications -------------------------------------------------------------------------------------------

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

    // Operations ----------------------------------------------------------------------------------------------

    @Bean
    SystemStatusQuery systemStatusQuery(List<IntegrationStatusSource> sources, ProcessingOverviewSource processing,
            HouseholdAccess access, Clock clock) {
        return new IntegrationStatusService(sources, processing, access, clock);
    }

    // Portability ---------------------------------------------------------------------------------------------

    record PortabilityServices(ExportService exports) {
    }

    @Bean
    PortabilityServices portabilityServices(DocumentExportQuery documents, TaskExportQuery tasks,
            MemberDirectory members, ExportStore store, ExportSerializer serializer, HouseholdAccess access,
            Clock clock, @Value("${aegis.build.commit:dev}") String commit) {
        return new PortabilityServices(new ExportService(documents, tasks, members, store, serializer, access, clock,
                "aegis@" + commit));
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
            ImportTask importTask, HouseholdAccess access) {
        return new RestoreService(opener, serializer, members, provisioning, importDocument, importCatalog,
                importTask, access);
    }
}
