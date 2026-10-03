package dev.aegis.notifications.adapters.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Checks the Web Push encryption by decrypting as the browser does (RFC 8291 section 3.4) and the VAPID token by
 * verifying it with the public key the browser subscribed with.
 */
class WebPushCryptoTest {
    private static final Base64.Decoder BASE64URL = Base64.getUrlDecoder();

    @Test
    void theBrowserCanDecryptTheMessageWithItsPrivateKeyAndAuthSecret() throws Exception {
        KeyPair browser = EcKeys.generate();
        byte[] browserPublic = EcKeys.encode((ECPublicKey) browser.getPublic());
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        byte[] message = "{\"title\":\"Aegis\",\"body\":\"Grüße\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = new WebPushEncryption(new SecureRandom()).encrypt(message, browserPublic, auth);

        ByteBuffer header = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        header.get(salt);
        assertThat(header.getInt()).isEqualTo(4096);
        int keyLength = header.get();
        assertThat(keyLength).isEqualTo(65);
        byte[] serverPublic = new byte[keyLength];
        header.get(serverPublic);
        byte[] ciphertext = Arrays.copyOfRange(body, header.position(), body.length);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(EcKeys.decode(serverPublic), true);
        byte[] keyInfo = WebPushEncryption.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                browserPublic, serverPublic);
        byte[] ikm = WebPushEncryption.hkdf(auth, agreement.generateSecret(), keyInfo, 32);
        byte[] cek = WebPushEncryption.hkdf(salt, ikm,
                "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushEncryption.hkdf(salt, ikm,
                "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plaintext = cipher.doFinal(ciphertext);

        assertThat(plaintext[plaintext.length - 1]).as("last-record delimiter").isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(plaintext, plaintext.length - 1)).isEqualTo(message);
    }

    @Test
    void eachMessageUsesAFreshKeyAndSalt() {
        byte[] browserPublic = EcKeys.encode((ECPublicKey) EcKeys.generate().getPublic());
        WebPushEncryption encryption = new WebPushEncryption(new SecureRandom());
        byte[] first = encryption.encrypt(new byte[] {1}, browserPublic, new byte[16]);
        byte[] second = encryption.encrypt(new byte[] {1}, browserPublic, new byte[16]);

        assertThat(Arrays.copyOf(first, 86)).isNotEqualTo(Arrays.copyOf(second, 86));
        assertThatThrownBy(() -> encryption.encrypt(new byte[WebPushEncryption.MAX_PLAINTEXT + 1], browserPublic,
                new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVapidTokenIsSignedForThePushServiceOriginAndVerifiesWithThePublicKey() throws Exception {
        KeyPair server = EcKeys.generate();
        String publicKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(EcKeys.encode((ECPublicKey) server.getPublic()));
        Instant now = Instant.parse("2026-10-03T10:00:00Z");

        String header = VapidSigner.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc"),
                "mailto:aegis@aegis.invalid", server.getPrivate(), publicKey, now);

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + publicKey);
        String token = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        String claims = new String(BASE64URL.decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).isEqualTo("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":"
                + now.plus(VapidSigner.TOKEN_LIFETIME).getEpochSecond() + ",\"sub\":\"mailto:aegis@aegis.invalid\"}");

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(EcKeys.decode(BASE64URL.decode(publicKey)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(BASE64URL.decode(parts[2]))).isTrue();
    }

    @Test
    void pushServiceAnswersAreClassified() {
        assertThat(WebPushSender.classify(201)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.ACCEPTED);
        assertThat(WebPushSender.classify(410)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.GONE);
        assertThat(WebPushSender.classify(404)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.GONE);
        assertThat(WebPushSender.classify(429)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.UNAVAILABLE);
        assertThat(WebPushSender.classify(503)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.UNAVAILABLE);
        assertThat(WebPushSender.classify(403)).isEqualTo(dev.aegis.notifications.application.push.PushSender
                .Outcome.REJECTED);
    }
}
