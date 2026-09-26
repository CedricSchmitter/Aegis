package dev.aegis.identity.application.api;

import dev.aegis.shared.ProductLocale;

/** Repeatable bootstrap of the household logins. Existing members are left untouched. */
public interface ProvisionMembers {

    /**
     * {@code kind} is optional; without it the member is a person. {@code appearance} is optional; without it the
     * member starts with the default (a restore passes the exported preference of a member it has to create).
     */
    record ProvisionMemberCommand(String username, String displayName, String initialPassword, ProductLocale locale,
            MemberKind kind, MemberAppearance appearance) {
        public ProvisionMemberCommand(String username, String displayName, String initialPassword,
                ProductLocale locale, MemberKind kind) {
            this(username, displayName, initialPassword, locale, kind, null);
        }

        public ProvisionMemberCommand(String username, String displayName, String initialPassword,
                ProductLocale locale) {
            this(username, displayName, initialPassword, locale, null, null);
        }
    }

    enum ProvisioningOutcome {
        CREATED,
        ALREADY_EXISTS
    }

    ProvisioningOutcome ensureMember(ProvisionMemberCommand command);
}
