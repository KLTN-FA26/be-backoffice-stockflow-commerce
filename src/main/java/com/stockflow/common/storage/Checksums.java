package com.stockflow.common.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 in the two spellings this code meets: lower-case hex, which the database stores and every
 * API returns, and base64, which S3 sends and expects in {@code x-amz-checksum-sha256}.
 */
public final class Checksums {

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    private Checksums() {
    }

    public static boolean isSha256Hex(String value) {
        return value != null && HEX.matcher(value).matches();
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JDK ships SHA-256 (it is a required algorithm of the platform).
            throw new IllegalStateException(impossible);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** {@code x-amz-checksum-sha256} for a hex digest. */
    public static String hexToBase64(String hex) {
        if (!isSha256Hex(hex)) {
            throw new IllegalArgumentException("Not a SHA-256 hex digest");
        }
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    /**
     * The hex digest of an S3 {@code ChecksumSHA256}, or null when it is absent or not a whole-object
     * digest. A multipart upload reports a checksum of its part checksums ({@code <base64>-<parts>}),
     * which is not the SHA-256 of the object and must never be compared with one.
     */
    public static String base64ToHexOrNull(String base64) {
        if (base64 == null || base64.isBlank() || base64.contains("-")) {
            return null;
        }
        try {
            byte[] digest = Base64.getDecoder().decode(base64);
            return digest.length == 32 ? HexFormat.of().formatHex(digest) : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
