package dev.aegis.notifications.domain.push;

import dev.aegis.shared.InvalidInputException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a browser receives pushes and how to encrypt for it (RFC 8030, RFC 8291): the push service URL, the browser's
 * P-256 public key and its authentication secret, both base64url as the Push API reports them. Only HTTPS URLs on a
 * push service host are accepted, so a subscription cannot make the server post to an arbitrary address.
 */
public record PushEndpoint(String url, String p256dh, String auth) {
    private static final int MAX_URL_LENGTH = 1000;

    public PushEndpoint {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(p256dh, "p256dh");
        Objects.requireNonNull(auth, "auth");
        if (url.length() > MAX_URL_LENGTH) {
            throw invalid("push endpoint URL is too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid("push endpoint is not a URL");
        }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw invalid("push endpoint must be an https URL");
        }
        byte[] key = decode(p256dh, "p256dh");
        if (key.length != 65 || key[0] != 0x04) {
            throw invalid("p256dh must be an uncompressed P-256 point");
        }
        if (decode(auth, "auth").length != 16) {
            throw invalid("auth must be 16 bytes");
        }
    }

    /** Lower-case host of the push service. */
    public String host() {
        return URI.create(url).getHost().toLowerCase(Locale.ROOT);
    }

    public byte[] publicKey() {
        return Base64.getUrlDecoder().decode(strip(p256dh));
    }

    public byte[] authSecret() {
        return Base64.getUrlDecoder().decode(strip(auth));
    }

    private static byte[] decode(String value, String field) {
        try {
            return Base64.getUrlDecoder().decode(strip(value));
        } catch (IllegalArgumentException e) {
            throw invalid(field + " is not base64url");
        }
    }

    private static String strip(String value) {
        return value.replace("=", "");
    }

    private static InvalidInputException invalid(String message) {
        return new InvalidInputException("push.subscription.invalid", message);
    }
}
