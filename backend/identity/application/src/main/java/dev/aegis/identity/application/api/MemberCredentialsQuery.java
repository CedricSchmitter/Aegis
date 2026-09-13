package dev.aegis.identity.application.api;

import java.util.Optional;

/** Used by the authentication adapter to verify logins. Passwords are compared by the adapter's encoder. */
public interface MemberCredentialsQuery {
    Optional<MemberCredentials> findCredentials(String username);
}
