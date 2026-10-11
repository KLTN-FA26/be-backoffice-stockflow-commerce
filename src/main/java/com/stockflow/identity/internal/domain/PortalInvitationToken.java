package com.stockflow.identity.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The secret a portal invitation e-mail carries: the invited account's id plus 256 random bits.
 *
 * <p>The account id travels in the clear so redemption can lock that one row before it compares
 * anything; it is not a secret and proves nothing. Only the SHA-256 digest of the whole value is
 * ever stored ({@link PortalInvitation}), so a database read does not yield a usable link. The raw
 * value exists in memory between issue and the after-commit e-mail, and nowhere else.</p>
 */
public final class PortalInvitationToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    /** 16 id bytes + 32 secret bytes = 48 bytes = exactly 64 base64url characters, no padding. */
    private static final int ENCODED_LENGTH = 64;

    private final UUID userId;
    private final String value;

    private PortalInvitationToken(UUID userId, String value) {
        this.userId = userId;
        this.value = value;
    }

    public static PortalInvitationToken generate(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        ByteBuffer bytes = ByteBuffer.allocate(16 + SECRET_BYTES)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .put(secret);
        return new PortalInvitationToken(userId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array()));
    }

    /** Empty for anything that is not a canonical token, including alternate base64 spellings. */
    public static Optional<PortalInvitationToken> parse(String value) {
        if (value == null || value.length() != ENCODED_LENGTH || !value.matches("[A-Za-z0-9_-]+")) {
            return Optional.empty();
        }
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return Optional.of(new PortalInvitationToken(new UUID(buffer.getLong(), buffer.getLong()), value));
    }

    public UUID userId() { return userId; }

    /** The raw value, for the e-mail link only. Never log it, persist it or return it to an API caller. */
    public String value() { return value; }

    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Override
    public String toString() { return "PortalInvitationToken[REDACTED]"; }
}
