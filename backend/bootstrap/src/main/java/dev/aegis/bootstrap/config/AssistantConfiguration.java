package dev.aegis.bootstrap.config;

import dev.aegis.assistant.adapters.config.AiProperties;
import dev.aegis.assistant.application.AiRunService;
import dev.aegis.assistant.application.AiSettings;
import dev.aegis.assistant.application.ConnectionCheckHandler;
import dev.aegis.assistant.application.RunExecution;
import dev.aegis.assistant.application.RunExecutionService;
import dev.aegis.assistant.application.RunExecutor;
import dev.aegis.assistant.application.WorkloadHandler;
import dev.aegis.assistant.application.chat.ChatHandler;
import dev.aegis.assistant.application.chat.ChatRetrieval;
import dev.aegis.assistant.application.chat.ChatService;
import dev.aegis.assistant.application.api.AiHealthQuery;
import dev.aegis.assistant.application.api.AiModelCommands;
import dev.aegis.assistant.application.api.AiRunCommands;
import dev.aegis.assistant.application.api.AiRunQueries;
import dev.aegis.assistant.application.api.AiRunStatusQuery;
import dev.aegis.assistant.application.api.ChatCommands;
import dev.aegis.assistant.application.api.ChatQueries;
import dev.aegis.assistant.application.api.RequestAiRun;
import dev.aegis.assistant.application.ports.AiLock;
import dev.aegis.assistant.application.ports.AiTool;
import dev.aegis.assistant.application.ports.EffectLedger;
import dev.aegis.assistant.application.ports.ModelGateway;
import dev.aegis.assistant.application.ports.RunScheduler;
import dev.aegis.assistant.domain.AiRunRepository;
import dev.aegis.assistant.domain.Workload;
import dev.aegis.assistant.domain.WorkloadModelChoiceRepository;
import dev.aegis.assistant.domain.chat.ConversationRepository;
import dev.aegis.contracts.application.api.ContractQueries;
import dev.aegis.documents.application.api.DocumentTextQuery;
import dev.aegis.planning.application.api.TaskQueries;
import dev.aegis.shared.HouseholdAccess;
import java.time.Clock;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the AI execution boundary (ADR-0003): workload handlers, model gateways and tools are collected by name;
 * the transactional run steps and the member use cases are exposed through transaction proxies; the executor itself
 * runs outside transactions.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AssistantConfiguration {

    @Bean
    AiSettings aiSettings(AiProperties properties, @Value("${aegis.household.zone:Europe/Berlin}") String zone) {
        return properties.toSettings(ZoneId.of(zone));
    }

    @Bean
    WorkloadHandler connectionCheckHandler() {
        return new ConnectionCheckHandler();
    }

    @Bean
    WorkloadHandler chatHandler(ConversationRepository conversations, DocumentTextQuery documentTexts,
            ContractQueries contracts, TaskQueries tasks, AiSettings settings, Clock clock,
            @Value("${aegis.ai.chat-retrieval:LABELS_AND_TEXT}") ChatRetrieval retrieval) {
        return new ChatHandler(conversations, documentTexts, contracts, tasks, settings.zone(), clock, retrieval);
    }

    /** Handlers by workload, gateways by provider and tools by name; duplicates are configuration errors. */
    record AssistantParts(Map<Workload, WorkloadHandler> handlers, Map<String, ModelGateway> gateways,
            Map<String, AiTool> tools) {
    }

    @Bean
    AssistantParts assistantParts(List<WorkloadHandler> handlers, List<ModelGateway> gateways, List<AiTool> tools) {
        Map<Workload, WorkloadHandler> byWorkload = new EnumMap<>(Workload.class);
        byWorkload.putAll(unique(handlers, WorkloadHandler::workload, "workload handler"));
        return new AssistantParts(byWorkload, unique(gateways, ModelGateway::provider, "model gateway"),
                unique(tools, tool -> tool.spec().name(), "AI tool"));
    }

    private static <K, V> Map<K, V> unique(List<V> values, Function<V, K> key, String kind) {
        Map<K, V> map = new LinkedHashMap<>();
        for (V value : values) {
            if (map.put(key.apply(value), value) != null) {
                throw new IllegalStateException("duplicate " + kind + " " + key.apply(value));
            }
        }
        return map;
    }

    @Bean
    RunExecution runExecution(AiRunRepository runs, AiLock lock, EffectLedger ledger, RunScheduler scheduler,
            AssistantParts parts, HouseholdAccess access, AiSettings settings, Clock clock,
            PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RunExecution.class,
                new RunExecutionService(runs, lock, ledger, scheduler, parts.handlers(), access, settings, clock), tm);
    }

    @Bean
    RunExecutor runExecutor(RunExecution execution, AssistantParts parts, AiSettings settings) {
        return new RunExecutor(execution, parts.handlers(), parts.gateways(), parts.tools(), settings);
    }

    record AssistantServices(AiRunService runs, ChatService chat) {
    }

    @Bean
    AssistantServices assistantServices(AiRunRepository runs, WorkloadModelChoiceRepository choices,
            ConversationRepository conversations, RunScheduler scheduler, AiLock lock, AssistantParts parts,
            AiSettings settings, HouseholdAccess access, Clock clock) {
        AiRunService service = new AiRunService(runs, choices, scheduler, lock, parts.handlers(), parts.gateways(),
                settings, access, clock);
        return new AssistantServices(service, new ChatService(conversations, runs, service, service, service, access,
                clock));
    }

    @Bean
    ChatCommands chatCommands(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ChatCommands.class, services.chat(), tm);
    }

    @Bean
    ChatQueries chatQueries(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(ChatQueries.class, services.chat(), tm);
    }

    @Bean
    RequestAiRun requestAiRun(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(RequestAiRun.class, services.runs(), tm);
    }

    @Bean
    AiRunQueries aiRunQueries(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(AiRunQueries.class, services.runs(), tm);
    }

    @Bean
    AiRunCommands aiRunCommands(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(AiRunCommands.class, services.runs(), tm);
    }

    @Bean
    AiModelCommands aiModelCommands(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(AiModelCommands.class, services.runs(), tm);
    }

    @Bean
    AiRunStatusQuery aiRunStatusQuery(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(AiRunStatusQuery.class, services.runs(), tm);
    }

    @Bean
    AiHealthQuery aiHealthQuery(AssistantServices services, PlatformTransactionManager tm) {
        return TransactionalProxy.wrap(AiHealthQuery.class, services.runs(), tm);
    }
}
