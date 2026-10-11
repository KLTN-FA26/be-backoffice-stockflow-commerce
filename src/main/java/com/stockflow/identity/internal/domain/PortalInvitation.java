package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one-time credential of a customer account that Sales created and nobody has activated yet.
 *
 * <h2>Why it lives in {@code app_user.password_hash}</h2>
 *
 * <p>An invited account has no password: its only credential is the link in the invitation e-mail.
 * The schema has no invitation table, and the database owner's schema is not ours to extend, so
 * the account stores that credential where every other account stores its own - as a value
 * {@code PasswordEncoder} can never match ({@code {portal-invitation}} is no encoder id). That one
 * column then carries the whole lifecycle under the account's own row lock: issue writes it,
 * resend replaces it (so the previous link stops working), activation replaces it with a real
 * password hash (so the link works once). There is no second row that could drift from the account.
 * Only the digest of the token is stored.</p>
 *
 * <p>Instants are kept to the second: the encoding is text, and nothing here needs more.</p>
 */
public final class PortalInvitation {

    static final String PREFIX = "{portal-invitation}";
    private static final Pattern ENCODED = Pattern.compile(
            Pattern.quote(PREFIX) + "(\\d{1,19}):(\\d{1,19}):([0-9a-f]{64})");

    private final Instant issuedAt;
    private final Instant expiresAt;
    private final String tokenDigest;

    private PortalInvitation(Instant issuedAt, Instant expiresAt, String tokenDigest) {
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be after issuedAt");
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.tokenDigest = tokenDigest;
    }

    public static PortalInvitation issue(PortalInvitationToken token, Instant now, Duration lifetime) {
        Objects.requireNonNull(token, "token");
        if (lifetime == null || lifetime.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("An invitation must live at least a minute");
        }
        Instant issued = now.truncatedTo(ChronoUnit.SECONDS);
        return new PortalInvitation(issued, issued.plus(lifetime), token.digest());
    }

    /**
     * Empty when the stored value is an ordinary password hash. A value carrying the prefix but not
     * the format is corrupt data, not a password, so it fails loudly instead of being matched.
     */
    public static Optional<PortalInvitation> fromCredential(String credential) {
        if (credential == null || !credential.startsWith(PREFIX)) return Optional.empty();
        var matcher = ENCODED.matcher(credential);
        if (!matcher.matches()) throw new IllegalStateException("Malformed portal invitation credential");
        return Optional.of(new PortalInvitation(Instant.ofEpochSecond(Long.parseLong(matcher.group(1))),
                Instant.ofEpochSecond(Long.parseLong(matcher.group(2))), matcher.group(3)));
    }

    public String toCredential() {
        return PREFIX + issuedAt.getEpochSecond() + ":" + expiresAt.getEpochSecond() + ":" + tokenDigest;
    }

    /** The exact expiry instant is already too late. Compared in constant time. */
    public boolean redeemableBy(PortalInvitationToken token, Instant now) {
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt)
                && MessageDigest.isEqual(tokenDigest.getBytes(StandardCharsets.US_ASCII),
                        token.digest().getBytes(StandardCharsets.US_ASCII));
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** A resend within the cooldown would let one click-happy screen flood the customer's inbox. */
    void requireReplaceableAt(Instant now, Duration cooldown) {
        if (now.isBefore(issuedAt.plus(cooldown))) {
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "The previous invitation was sent less than %s ago".formatted(cooldown));
        }
    }

    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }

    @Override
    public String toString() {
        return "PortalInvitation[issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
