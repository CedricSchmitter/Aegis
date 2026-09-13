package dev.aegis.bootstrap.config;

import dev.aegis.documents.application.ArchivalService;
import dev.aegis.documents.application.ArchivalSettings;
import dev.aegis.documents.application.ArchivalUseCases;
import dev.aegis.documents.application.DocumentCaptureService;
import dev.aegis.documents.application.DocumentCommandService;
import dev.aegis.documents.application.DocumentPresenter;
import dev.aegis.documents.application.DocumentQueryService;
import dev.aegis.documents.application.DocumentRegistrar;
import dev.aegis.documents.application.ReconciliationService;
import dev.aegis.documents.application.RegisterReceivedDocument;
import dev.aegis.documents.application.api.DocumentCommands;
import dev.aegis.documents.application.api.DocumentExportQuery;
import dev.aegis.documents.application.api.DocumentQueries;
import dev.aegis.documents.application.api.DocumentReferenceQuery;
import dev.aegis.documents.application.api.DocumentStatusQuery;
import dev.aegis.documents.application.api.ImportDocument;
import dev.aegis.documents.application.api.UploadDocument;
import dev.aegis.documents.application.ports.DocumentEvents;
import dev.aegis.documents.application.ports.OriginalStore;
import dev.aegis.documents.application.ports.PaperlessArchive;
import dev.aegis.documents.application.ports.ProcessingScheduler;
import dev.aegis.documents.domain.DocumentRepository;
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
 * Raw service objects are not autowire candidates so that only the decorated interfaces are injected.
 */
@Configuration
public class UseCaseConfiguration {

    // Identity ------------------------------------------------------------------------------------------------

    @Bean
    HouseholdAccess householdAccess(MemberRepository members) {
        return new HouseholdAccessPolicy(members);
    }

    @Bean(autowireCandidate = false)
    MemberService memberService(MemberRepository members, PasswordHasher hasher, HouseholdAccess access,
            Clock clock) {
        return new MemberService(members, hasher, access, clock);
    }

    @Bean
    MemberDirectory memberDirectory(MemberService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberDirectory.class, service, tm);
    }

    @Bean
    MemberCredentialsQuery memberCredentialsQuery(MemberService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberCredentialsQuery.class, service, tm);
    }

    @Bean
    ProvisionMembers provisionMembers(MemberService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ProvisionMembers.class, service, tm);
    }

    @Bean
    MemberPreferences memberPreferences(MemberService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(MemberPreferences.class, service, tm);
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
    DocumentPresenter documentPresenter(ProcessingScheduler scheduler) {
        return new DocumentPresenter(scheduler);
    }

    @Bean
    RegisterReceivedDocument registerReceivedDocument(DocumentRepository documents, ProcessingScheduler scheduler,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RegisterReceivedDocument.class, new DocumentRegistrar(documents, scheduler),
                tm);
    }

    @Bean(autowireCandidate = false)
    DocumentCaptureService documentCaptureService(OriginalStore store, RegisterReceivedDocument registrar,
            DocumentPresenter presenter, HouseholdAccess access, ArchivalSettings settings, Clock clock) {
        return new DocumentCaptureService(store, registrar, presenter, access, settings, clock);
    }

    @Bean
    UploadDocument uploadDocument(DocumentCaptureService service) {
        return service;
    }

    @Bean
    ImportDocument importDocument(DocumentCaptureService service) {
        return service;
    }

    @Bean(autowireCandidate = false)
    DocumentQueryService documentQueryService(DocumentRepository documents, PaperlessArchive archive,
            OriginalStore store, DocumentPresenter presenter, HouseholdAccess access, Clock clock) {
        return new DocumentQueryService(documents, archive, store, presenter, access, clock);
    }

    @Bean
    DocumentQueries documentQueries(DocumentQueryService service) {
        return service;
    }

    @Bean
    DocumentReferenceQuery documentReferenceQuery(DocumentQueryService service) {
        return service;
    }

    @Bean
    DocumentExportQuery documentExportQuery(DocumentQueryService service) {
        return service;
    }

    @Bean
    DocumentStatusQuery documentStatusQuery(DocumentQueryService service) {
        return service;
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

    @Bean(autowireCandidate = false)
    TaskCommandService taskCommandService(TaskRepository tasks, DocumentReferenceQuery documents,
            PlanningEvents events, TaskPresenter presenter, HouseholdAccess access, Clock clock) {
        return new TaskCommandService(tasks, documents, events, presenter, access, clock);
    }

    @Bean
    TaskCommands taskCommands(TaskCommandService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(TaskCommands.class, service, tm);
    }

    @Bean
    ImportTask importTask(TaskCommandService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ImportTask.class, service, tm);
    }

    @Bean(autowireCandidate = false)
    TaskQueryService taskQueryService(TaskRepository tasks, CalendarPort calendar, TaskPresenter presenter,
            HouseholdAccess access, Clock clock) {
        return new TaskQueryService(tasks, calendar, presenter, access, clock);
    }

    @Bean
    TaskQueries taskQueries(TaskQueryService service) {
        return service;
    }

    @Bean
    TaskExportQuery taskExportQuery(TaskQueryService service) {
        return service;
    }

    // Notifications -------------------------------------------------------------------------------------------

    @Bean(autowireCandidate = false)
    NoticeService noticeService(NoticeRepository notices, HouseholdAccess access, Clock clock) {
        return new NoticeService(notices, access, clock);
    }

    @Bean
    PublishNotice publishNotice(NoticeService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(PublishNotice.class, service, tm);
    }

    @Bean
    NoticeInbox noticeInbox(NoticeService service, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(NoticeInbox.class, service, tm);
    }

    // Operations ----------------------------------------------------------------------------------------------

    @Bean
    SystemStatusQuery systemStatusQuery(List<IntegrationStatusSource> sources, ProcessingOverviewSource processing,
            HouseholdAccess access, Clock clock) {
        return new IntegrationStatusService(sources, processing, access, clock);
    }

    // Portability ---------------------------------------------------------------------------------------------

    @Bean(autowireCandidate = false)
    ExportService exportService(DocumentExportQuery documents, TaskExportQuery tasks, MemberDirectory members,
            ExportStore store, ExportSerializer serializer, HouseholdAccess access, Clock clock,
            @Value("${aegis.build.commit:dev}") String commit) {
        return new ExportService(documents, tasks, members, store, serializer, access, clock, "aegis@" + commit);
    }

    @Bean
    CreateExport createExport(ExportService service) {
        return service;
    }

    @Bean
    ExportQueries exportQueries(ExportService service) {
        return service;
    }

    @Bean
    RestoreExport restoreExport(ExportSourceOpener opener, ExportSerializer serializer, MemberDirectory members,
            ProvisionMembers provisioning, ImportDocument importDocument, ImportTask importTask,
            HouseholdAccess access) {
        return new RestoreService(opener, serializer, members, provisioning, importDocument, importTask, access);
    }
}
