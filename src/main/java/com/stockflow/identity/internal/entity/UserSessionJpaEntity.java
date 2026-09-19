package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.identity.internal.domain.SessionEndReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of {@code identity.user_session}: one row per issued token. The row id is the token's
 * {@code sid}/{@code jti} claim, so the resource server can ask "is this session still live" with a
 * primary-key lookup. Not the domain model.
 */
@Entity
@Table(name = "user_session", schema = "identity")
public class UserSessionJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason", length = 32)
    private SessionEndReason revokedReason;

    @Column(name = "client_address", length = 45, updatable = false)
    private String clientAddress;

    @Column(name = "user_agent", length = 255, updatable = false)
    private String userAgent;

    protected UserSessionJpaEntity() {
    }

    public UserSessionJpaEntity(UUID id, UUID userId, Instant issuedAt, Instant expiresAt,
                                String clientAddress, String userAgent) {
        super(id);
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.clientAddress = truncate(clientAddress, 45);
        this.userAgent = truncate(userAgent, 255);
    }

    /** Ends the session. Idempotent: the first reason and time are kept. */
    public void revoke(Instant now, SessionEndReason reason) {
        if (revokedAt == null) {
            this.revokedAt = now;
            this.revokedReason = reason;
        }
    }

    public boolean isLiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getUserId() { return userId; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public SessionEndReason getRevokedReason() { return revokedReason; }
    public String getClientAddress() { return clientAddress; }
    public String getUserAgent() { return userAgent; }
}
