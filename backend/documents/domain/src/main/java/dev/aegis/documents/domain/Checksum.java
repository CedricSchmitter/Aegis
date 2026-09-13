package dev.aegis.documents.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** SHA-256 checksum of an original file as lowercase hex. Used for duplicate detection and export manifests. */
public record Checksum(String sha256Hex) {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public Checksum {
        if (sha256Hex == null || !HEX.matcher(sha256Hex).matches()) {
            throw new IllegalArgumentException("checksum must be 64 lowercase hex characters");
        }
    }

    public static Checksum of(String raw) {
        return new Checksum(raw == null ? null : raw.trim().toLowerCase(Locale.ROOT));
    }

    public boolean matches(byte[] bytes) {
        try {
            return sha256Hex.equals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public String toString() {
        return sha256Hex;
    }
}
