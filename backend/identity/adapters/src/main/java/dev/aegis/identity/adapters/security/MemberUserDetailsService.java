package dev.aegis.identity.adapters.security;

import dev.aegis.identity.application.api.MemberCredentialsQuery;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/** Bridges Spring Security login to the identity application. */
@Component
public class MemberUserDetailsService implements UserDetailsService {
    private final MemberCredentialsQuery credentials;

    public MemberUserDetailsService(MemberCredentialsQuery credentials) {
        this.credentials = credentials;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return credentials.findCredentials(username)
                .map(c -> new MemberPrincipal(c.id(), c.username(), c.passwordHash(), c.active()))
                .orElseThrow(() -> new UsernameNotFoundException("unknown member"));
    }
}
