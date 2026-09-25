package dev.aegis.identity.application.api;

import dev.aegis.shared.ProductLocale;

/** Repeatable bootstrap of the household logins. Existing members are left untouched. */
public interface ProvisionMembers {

    /** {@code kind} is optional; without it the member is a person. */
    record ProvisionMemberCommand(String username, String displayName, String initialPassword, ProductLocale locale,
            MemberKind kind) {
        public ProvisionMemberCommand(String username, String displayName, String initialPassword,
                ProductLocale locale) {
            this(username, displayName, initialPassword, locale, null);
        }
    }

    enum ProvisioningOutcome {
        CREATED,
        ALREADY_EXISTS
    }

    ProvisioningOutcome ensureMember(ProvisionMemberCommand command);
}
