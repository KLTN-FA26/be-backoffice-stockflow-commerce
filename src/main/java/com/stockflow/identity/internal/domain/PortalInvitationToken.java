package com.stockflow.identity.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** An opaque 256-bit secret. Persist only its digest; the raw value is for the mail link only. */
public final class PortalInvitationToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String secret;

    private PortalInvitationToken(String secret) {
        this.secret = secret;
    }

    public static PortalInvitationToken generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new PortalInvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public static PortalInvitationToken parse(String secret) {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid invitation token");
        }
        // Reject alternate base64 spellings for the same bytes.
        byte[] bytes = Base64.getUrlDecoder().decode(secret);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(secret)) {
            throw new IllegalArgumentException("Invalid invitation token");
        }
        return new PortalInvitationToken(secret);
    }

    public String secret() { return secret; }

    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Override
    public String toString() { return "PortalInvitationToken[REDACTED]"; }
}
