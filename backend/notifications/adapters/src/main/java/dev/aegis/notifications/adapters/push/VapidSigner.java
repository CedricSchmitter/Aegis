package dev.aegis.notifications.adapters.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * VAPID authorization (RFC 8292): an ES256 JSON Web Token for the push service's origin, signed with the server's key,
 * so the push service accepts messages only from the server the browser subscribed with.
 */
final class VapidSigner {
    /** Push services accept at most 24 hours; a shorter token limits the damage if one leaks. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private VapidSigner() {
    }

    /** The {@code Authorization} header value: {@code vapid t=<jwt>, k=<public key>}. */
    static String authorization(URI endpoint, String subject, PrivateKey privateKey, String publicKey, Instant now) {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        String header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        String claims = encode("{\"aud\":\"" + json(audience) + "\",\"exp\":"
                + now.plus(TOKEN_LIFETIME).getEpochSecond() + ",\"sub\":\"" + json(subject) + "\"}");
        String signingInput = header + "." + claims;
        try {
            // JOSE needs the raw r || s form, which the P1363 variant produces directly.
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String token = signingInput + "." + BASE64URL.encodeToString(signature.sign());
            return "vapid t=" + token + ", k=" + publicKey;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID token could not be signed", e);
        }
    }

    private static String encode(String json) {
        return BASE64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Escapes the few characters that could end a JSON string; audience and subject are configuration. */
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
