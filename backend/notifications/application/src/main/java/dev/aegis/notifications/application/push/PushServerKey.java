package dev.aegis.notifications.application.push;

import java.util.Optional;

/** The server's VAPID key pair; only the public key leaves the adapter. */
public interface PushServerKey {
    /** The uncompressed P-256 public key, base64url without padding; empty when the key is unavailable. */
    Optional<String> publicKey();
}
