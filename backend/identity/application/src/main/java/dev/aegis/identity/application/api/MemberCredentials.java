package dev.aegis.identity.application.api;

import dev.aegis.shared.MemberId;

/** Credential view for the authentication adapter only. Not part of household data. */
public record MemberCredentials(MemberId id, String username, String passwordHash, boolean active) {
}
