package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.identity.internal.domain.PortalInvitation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code identity.portal_invitation}. Holds the token digest, never the token. */
@Entity
@Table(name = "portal_invitation", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_portal_invitation_digest", columnNames = "token_digest"))
public class PortalInvitationJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_digest", nullable = false, updatable = false, length = 64, columnDefinition = "bpchar(64)")
    private String tokenDigest;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "issued_by", updatable = false)
    private UUID issuedBy;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 16)
    private PortalInvitation.Delivery deliveryStatus;

    @Column(name = "delivery_attempted_at")
    private Instant deliveryAttemptedAt;

    protected PortalInvitationJpaEntity() {
    }

    public PortalInvitationJpaEntity(UUID id, UUID userId, String tokenDigest, Instant issuedAt,
                                     Instant expiresAt, UUID issuedBy) {
        super(id);
        this.userId = userId;
        this.tokenDigest = tokenDigest;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.issuedBy = issuedBy;
    }

    public void apply(Instant consumedAt, Instant revokedAt, PortalInvitation.Delivery deliveryStatus,
                      Instant deliveryAttemptedAt) {
        this.consumedAt = consumedAt;
        this.revokedAt = revokedAt;
        this.deliveryStatus = deliveryStatus;
        this.deliveryAttemptedAt = deliveryAttemptedAt;
    }

    public UUID getUserId() { return userId; }
    public String getTokenDigest() { return tokenDigest; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getIssuedBy() { return issuedBy; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public PortalInvitation.Delivery getDeliveryStatus() { return deliveryStatus; }
    public Instant getDeliveryAttemptedAt() { return deliveryAttemptedAt; }
}
