package dev.aegis.notifications.adapters.push;

import dev.aegis.notifications.application.push.PushServerKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The server's VAPID key pair in {@code push_server_keys}, created on first use. Kept in the database so a restore
 * brings it back together with the subscriptions made for it; a new pair would make every browser subscribe again.
 * The private key never leaves this adapter.
 */
@Component
public class JdbcPushServerKey implements PushServerKey {
    record Pair(String publicKey, PrivateKey privateKey) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Pair cached;

    JdbcPushServerKey(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<String> publicKey() {
        return Optional.of(pair().publicKey());
    }

    Pair pair() {
        Pair current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = loadOrCreate();
            }
            return cached;
        }
    }

    private Pair loadOrCreate() {
        Optional<Pair> stored = load();
        if (stored.isPresent()) {
            return stored.get();
        }
        KeyPair generated = EcKeys.generate();
        jdbc.sql("""
                INSERT INTO push_server_keys (id, public_key, private_key, created_at) VALUES (1, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""")
                .params(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(EcKeys.encode((ECPublicKey) generated.getPublic())),
                        Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()),
                        Timestamp.from(clock.instant()))
                .update();
        // Another instance may have created the pair first; the stored one wins.
        return load().orElseThrow(() -> new IllegalStateException("push server key was not stored"));
    }

    private Optional<Pair> load() {
        return jdbc.sql("SELECT public_key, private_key FROM push_server_keys WHERE id = 1")
                .query((row, index) -> new Pair(row.getString("public_key"), privateKey(row.getString("private_key"))))
                .optional();
    }

    private static PrivateKey privateKey(String pkcs8) {
        try {
            return KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("stored push server key is unreadable", e);
        }
    }
}
