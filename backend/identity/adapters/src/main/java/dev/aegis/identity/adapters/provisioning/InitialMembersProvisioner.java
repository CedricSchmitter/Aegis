package dev.aegis.identity.adapters.provisioning;

import dev.aegis.identity.application.api.ProvisionMembers;
import dev.aegis.identity.application.api.ProvisionMembers.ProvisionMemberCommand;
import dev.aegis.shared.ProductLocale;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates the household logins from a server-side JSON file at startup. The file stays outside Git and outside the
 * container image; only usernames and outcomes are logged, never passwords.
 */
@Component
public class InitialMembersProvisioner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialMembersProvisioner.class);

    private final ProvisionMembers provisioning;
    private final ObjectMapper mapper;
    private final String membersFile;

    public InitialMembersProvisioner(ProvisionMembers provisioning, ObjectMapper mapper,
            @Value("${aegis.members.file:}") String membersFile) {
        this.provisioning = provisioning;
        this.mapper = mapper;
        this.membersFile = membersFile;
    }

    record MemberEntry(String username, String displayName, String initialPassword, ProductLocale locale) {
    }

    record MembersFile(List<MemberEntry> members) {
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (membersFile == null || membersFile.isBlank()) {
            log.info("No initial members file configured (aegis.members.file); skipping provisioning");
            return;
        }
        Path path = Path.of(membersFile);
        if (!Files.isRegularFile(path)) {
            log.warn("Initial members file {} does not exist; skipping provisioning", path);
            return;
        }
        MembersFile parsed = mapper.readValue(Files.readAllBytes(path), MembersFile.class);
        for (MemberEntry entry : parsed.members() == null ? List.<MemberEntry>of() : parsed.members()) {
            var outcome = provisioning.ensureMember(new ProvisionMemberCommand(entry.username(), entry.displayName(),
                    entry.initialPassword(), entry.locale()));
            log.info("Member provisioning for '{}': {}", entry.username(), outcome);
        }
    }
}
