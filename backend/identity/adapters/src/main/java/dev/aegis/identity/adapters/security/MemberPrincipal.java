package dev.aegis.identity.adapters.security;

import dev.aegis.shared.Actor;
import dev.aegis.shared.MemberId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Session principal: the member identity plus the credential view Spring Security needs during login. Serializable
 * because sessions are stored in the database; only the member UUID and username are persisted.
 */
public final class MemberPrincipal implements UserDetails {
    private static final long serialVersionUID = 1L;
    private static final List<GrantedAuthority> MEMBER_AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));

    private final UUID memberUuid;
    private final String username;
    private final transient String passwordHash;
    private final boolean active;

    public MemberPrincipal(MemberId memberId, String username, String passwordHash, boolean active) {
        this.memberUuid = memberId.value();
        this.username = username;
        this.passwordHash = passwordHash;
        this.active = active;
    }

    public MemberId memberId() {
        return new MemberId(memberUuid);
    }

    public Actor actor() {
        return new Actor(memberId());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return MEMBER_AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public String toString() {
        return "MemberPrincipal[" + username + "]";
    }
}
