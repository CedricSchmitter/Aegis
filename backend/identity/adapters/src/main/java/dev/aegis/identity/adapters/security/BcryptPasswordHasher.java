package dev.aegis.identity.adapters.security;

import dev.aegis.identity.domain.PasswordHash;
import dev.aegis.identity.domain.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Adapts the Spring Security password encoder to the domain hashing contract. */
@Component
public class BcryptPasswordHasher implements PasswordHasher {
    private final PasswordEncoder encoder;

    public BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public PasswordHash hash(String plainPassword) {
        return new PasswordHash(encoder.encode(plainPassword));
    }

    @Override
    public boolean matches(String plainPassword, PasswordHash hash) {
        return encoder.matches(plainPassword, hash.value());
    }
}
