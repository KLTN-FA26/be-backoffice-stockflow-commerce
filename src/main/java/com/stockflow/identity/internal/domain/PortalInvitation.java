package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One portal invitation e-mail to the contact of a Sales-created customer (SCRUM-450).
 *
 * <p>It ends exactly one way: <b>consumed</b> when the contact activates the account, or
 * <b>revoked</b> when Sales sends a newer one - which is how a resend kills the previous link. Only
 * the digest of the token is kept, so a database read never yields a working link. The same rules
 * are CHECK constraints on {@code identity.portal_invitation}, and "one open invitation per account"
 * is its partial unique index.</p>
 *
 * <p>Instants are kept to the second; nothing here needs more.</p>
 */
public final class PortalInvitation {

    public enum Delivery { SENT, FAILED }

    private final UUID id;
    private final UUID userId;
    private final String tokenDigest;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final UUID issuedBy;
    private Instant consumedAt;
    private Instant revokedAt;
    private Delivery delivery;
    private Instant deliveryAttemptedAt;
    private final long version;

    public PortalInvitation(UUID id, UUID userId, String tokenDigest, Instant issuedAt, Instant expiresAt,
                            UUID issuedBy, Instant consumedAt, Instant revokedAt, Delivery delivery,
                            Instant deliveryAttemptedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        if (tokenDigest == null || !tokenDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A SHA-256 token digest is required");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (consumedAt != null && revokedAt != null) {
            throw new IllegalArgumentException("An invitation is either consumed or revoked");
        }
        if ((delivery == null) != (deliveryAttemptedAt == null)) {
            throw new IllegalArgumentException("A delivery outcome needs its time, and only then");
        }
        this.tokenDigest = tokenDigest;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.issuedBy = issuedBy;
        this.consumedAt = consumedAt;
        this.revokedAt = revokedAt;
        this.delivery = delivery;
        this.deliveryAttemptedAt = deliveryAttemptedAt;
        this.version = version;
    }

    public static PortalInvitation issue(UUID id, PortalInvitationToken token, UUID issuedBy,
                                         Instant now, Duration lifetime) {
        Objects.requireNonNull(token, "token");
        if (lifetime == null || lifetime.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("An invitation must live at least a minute");
        }
        Instant issued = now.truncatedTo(ChronoUnit.SECONDS);
        return new PortalInvitation(id, token.userId(), token.digest(), issued, issued.plus(lifetime), issuedBy,
                null, null, null, null, 0L);
    }

    public boolean isOpen() {
        return consumedAt == null && revokedAt == null;
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Open, within its lifetime (the expiry instant is already too late), and this token. Constant time. */
    public boolean redeemableBy(PortalInvitationToken token, Instant now) {
        return isOpen() && token.userId().equals(userId) && !now.isBefore(issuedAt) && now.isBefore(expiresAt)
                && MessageDigest.isEqual(tokenDigest.getBytes(StandardCharsets.US_ASCII),
                        token.digest().getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws BusinessException {@code PORTAL_INVITATION_INVALID}, alike for every reason */
    public void consume(PortalInvitationToken token, Instant now) {
        if (!redeemableBy(token, now)) throw new BusinessException(ErrorCode.PORTAL_INVITATION_INVALID);
        consumedAt = now;
    }

    /**
     * Replaced by a newer invitation. An expired one may be replaced at once; an open one only after
     * {@code cooldown}, so one impatient screen cannot flood the customer's inbox.
     *
     * @throws BusinessException {@code RATE_LIMITED} within the cooldown
     */
    public void revokeForResend(Instant now, Duration cooldown) {
        if (!isOpen()) throw new IllegalStateException("Only an open invitation can be replaced");
        if (!expiredAt(now) && now.isBefore(issuedAt.plus(cooldown))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "The previous invitation was sent less than %s ago".formatted(cooldown));
        }
        revokedAt = now.isBefore(issuedAt) ? issuedAt : now;
    }

    /** The outcome of the e-mail, recorded after the commit that issued the invitation. */
    public void recordDelivery(Delivery outcome, Instant at) {
        this.delivery = Objects.requireNonNull(outcome, "outcome");
        this.deliveryAttemptedAt = Objects.requireNonNull(at, "at");
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String tokenDigest() { return tokenDigest; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public UUID issuedBy() { return issuedBy; }
    public Instant consumedAt() { return consumedAt; }
    public Instant revokedAt() { return revokedAt; }
    public Delivery delivery() { return delivery; }
    public Instant deliveryAttemptedAt() { return deliveryAttemptedAt; }
    public long version() { return version; }

    @Override
    public String toString() {
        return "PortalInvitation[id=" + id + ", userId=" + userId + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
