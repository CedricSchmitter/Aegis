package dev.aegis.identity.adapters.provisioning;

import dev.aegis.identity.application.api.DesignateAssistants;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Marks the accounts listed in {@code aegis.members.assistants} as the household's assistant at startup, after
 * provisioning and before any startup step that needs to know the persons (ADR-0017). Which account is the
 * assistant is environment data; the list is empty by default. Only usernames and outcomes are logged.
 */
@Component
@Order(11)
public class AssistantDesignationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AssistantDesignationRunner.class);

    private final DesignateAssistants designation;
    private final Set<String> usernames;
    private final String displayName;

    public AssistantDesignationRunner(DesignateAssistants designation,
            @Value("${aegis.members.assistants:}") List<String> usernames,
            @Value("${aegis.members.assistant-display-name:Aegis}") String displayName) {
        this.designation = designation;
        this.usernames = new LinkedHashSet<>();
        usernames.stream().filter(name -> name != null && !name.isBlank()).map(String::trim)
                .forEach(this.usernames::add);
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (usernames.isEmpty()) {
            return;
        }
        designation.designateAssistants(usernames, displayName).forEach(outcome -> {
            if (outcome.outcome() == DesignateAssistants.Outcome.UNKNOWN_USERNAME) {
                log.warn("Assistant designation: no member '{}' exists", outcome.username());
            } else {
                log.info("Assistant designation for '{}': {}", outcome.username(), outcome.outcome());
            }
        });
    }
}
