package dev.aegis.portability.adapters.restore;

import dev.aegis.identity.application.api.MemberCredentialsQuery;
import dev.aegis.portability.application.api.RestoreExport;
import dev.aegis.shared.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs a restore at startup when {@code aegis.import.path} is set (used by the isolated restore drill). Runs after
 * member provisioning so usernames can be matched; the instance keeps running for verification afterwards.
 */
@Component
@ConditionalOnProperty(name = "aegis.import.path")
@Order(20)
public class RestoreRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RestoreRunner.class);

    private final RestoreExport restore;
    private final MemberCredentialsQuery members;
    private final String importPath;
    private final String actorUsername;

    public RestoreRunner(RestoreExport restore, MemberCredentialsQuery members,
            @Value("${aegis.import.path}") String importPath,
            @Value("${aegis.import.actor-username:}") String actorUsername) {
        this.restore = restore;
        this.members = members;
        this.importPath = importPath;
        this.actorUsername = actorUsername;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (importPath == null || importPath.isBlank()) {
            return;
        }
        var credentials = members.findCredentials(actorUsername).orElseThrow(() -> new IllegalStateException(
                "aegis.import.actor-username must name a provisioned member; got '" + actorUsername + "'"));
        var report = restore.restore(new Actor(credentials.id()), importPath);
        log.info("Restore of generation {} from {}: documents imported={} present={}, tasks imported={} present={}, "
                + "labels created={}, folders created={}, classifications applied={} kept={}, member mappings={}, "
                + "warnings={}", report.generation(), importPath, report.documentsImported(),
                report.documentsAlreadyPresent(), report.tasksImported(), report.tasksAlreadyPresent(),
                report.labelsCreated(), report.foldersCreated(), report.classificationsApplied(),
                report.classificationsKept(), report.memberIdMapping().size(), report.warnings());
    }
}
