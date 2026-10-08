package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-use portal invitation rules, ready for the DB owner's persistence contract.
 * The repository must lock the customer's invitation and persist this transition in the SAME
 * transaction as identity creation and customer linkage. This object alone is not a concurrency lock.
 * The recipient and customer cannot be changed by resend or by the activation request.
 */
public final class PortalInvitation {
    private final UUID customerId;
    private final String email;
    private String tokenDigest;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant consumedAt;
    private final long version;

    public PortalInvitation(UUID customerId, String email, String tokenDigest, Instant issuedAt,
                            Instant expiresAt, Instant consumedAt, long version) {
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        this.email = email.strip().toLowerCase(java.util.Locale.ROOT);
        requireDigest(tokenDigest);
        requireLifetime(issuedAt, expiresAt);
        if (version < 0) throw new IllegalArgumentException("version must be nonnegative");
        if (consumedAt != null && (consumedAt.isBefore(issuedAt) || !consumedAt.isBefore(expiresAt))) {
            throw new IllegalArgumentException("consumedAt must be within the invitation lifetime");
        }
        this.tokenDigest = tokenDigest;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
        this.version = version;
    }

    /** The exact expiry instant is already expired; all unsuccessful redemption reasons look alike. */
    public void consume(String secret, Instant now, long expectedVersion) {
        Objects.requireNonNull(now, "now");
        String candidate;
        try {
            candidate = PortalInvitationToken.parse(secret).digest();
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        }
        if (consumedAt != null || now.isBefore(issuedAt) || !now.isBefore(expiresAt)
                || !MessageDigest.isEqual(tokenDigest.getBytes(StandardCharsets.US_ASCII),
                        candidate.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        }
        requireVersion(expectedVersion);
        consumedAt = now;
    }

    /** Rotation invalidates the previous link, including an expired one. Activated accounts never resend. */
    public void resend(String newDigest, Instant now, Instant newExpiry,
                       Duration cooldown, long expectedVersion) {
        requireDigest(newDigest);
        requireLifetime(now, newExpiry);
        if (cooldown == null || cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        if (consumedAt != null) throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        requireVersion(expectedVersion);
        if (now.isBefore(issuedAt.plus(cooldown))) throw new BusinessException(ErrorCode.RATE_LIMITED);
        if (tokenDigest.equals(newDigest)) throw new IllegalArgumentException("Resend requires a new secret");
        tokenDigest = newDigest;
        issuedAt = now;
        expiresAt = newExpiry;
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK);
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A SHA-256 token digest is required");
        }
    }

    private static void requireLifetime(Instant issuedAt, Instant expiresAt) {
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public UUID customerId() { return customerId; }
    public String email() { return email; }
    public String tokenDigest() { return tokenDigest; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public long version() { return version; }
}
