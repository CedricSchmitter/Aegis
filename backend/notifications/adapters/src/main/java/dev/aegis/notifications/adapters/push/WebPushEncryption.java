package dev.aegis.notifications.adapters.push;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Message encryption for Web Push (RFC 8291) in the aes128gcm content coding (RFC 8188), with JDK cryptography only.
 * Only the browser holding the subscription's private key can read a message; the push service sees ciphertext.
 */
final class WebPushEncryption {
    /** Record size announced in the header; one record carries the whole message. */
    private static final int RECORD_SIZE = 4096;
    /** Largest plaintext that fits one record: record size minus the 16-byte tag and the delimiter. */
    static final int MAX_PLAINTEXT = RECORD_SIZE - 17;

    private final SecureRandom random;

    WebPushEncryption(SecureRandom random) {
        this.random = random;
    }

    byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret) {
        KeyPair ephemeral = EcKeys.generate();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return encrypt(plaintext, browserPublicKey, authSecret, ephemeral, salt);
    }

    /** Deterministic form for tests: the ephemeral key pair and salt are given. */
    static byte[] encrypt(byte[] plaintext, byte[] browserPublicKey, byte[] authSecret, KeyPair ephemeral,
            byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("push message too large");
        }
        try {
            ECPublicKey browserKey = EcKeys.decode(browserPublicKey);
            byte[] serverPublicKey = EcKeys.encode((ECPublicKey) ephemeral.getPublic());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ephemeral.getPrivate());
            agreement.doPhase(browserKey, true);
            byte[] sharedSecret = agreement.generateSecret();

            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), browserPublicKey,
                    serverPublicKey);
            byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);
            byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
            byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            // A single, last record: the plaintext followed by the 0x02 delimiter and no padding.
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + serverPublicKey.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) serverPublicKey.length).put(serverPublicKey);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("push message could not be encrypted", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) for outputs of at most one hash length. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = mac.doFinal(concat(info, new byte[] {1}));
        return Arrays.copyOf(okm, length);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
