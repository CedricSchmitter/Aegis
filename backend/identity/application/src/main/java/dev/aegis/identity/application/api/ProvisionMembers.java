package dev.aegis.identity.application.api;

import dev.aegis.shared.ProductLocale;

/** Repeatable bootstrap of the two household logins. Existing members are left untouched. */
public interface ProvisionMembers {

    record ProvisionMemberCommand(String username, String displayName, String initialPassword, ProductLocale locale) {
    }

    enum ProvisioningOutcome {
        CREATED,
        ALREADY_EXISTS
    }

    ProvisioningOutcome ensureMember(ProvisionMemberCommand command);
}
