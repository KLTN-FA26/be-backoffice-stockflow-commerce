package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.PortalInvitation;
import com.stockflow.identity.internal.domain.PortalInvitationRepository;
import com.stockflow.identity.internal.entity.PortalInvitationJpaEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Adapts {@link PortalInvitationRepository} onto {@link PortalInvitationJpaRepository}. */
@Repository
class PortalInvitationRepositoryAdapter implements PortalInvitationRepository {

    private final PortalInvitationJpaRepository invitations;

    PortalInvitationRepositoryAdapter(PortalInvitationJpaRepository invitations) {
        this.invitations = invitations;
    }

    @Override
    public Optional<PortalInvitation> findOpenByUserId(UUID userId) {
        return invitations.findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(userId)
                .map(PortalInvitationRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<PortalInvitation> findById(UUID id) {
        return invitations.findById(id).map(PortalInvitationRepositoryAdapter::toDomain);
    }

    @Override
    public PortalInvitation save(PortalInvitation invitation) {
        PortalInvitationJpaEntity row = invitations.findById(invitation.id()).orElseGet(() ->
                new PortalInvitationJpaEntity(invitation.id(), invitation.userId(), invitation.tokenDigest(),
                        invitation.issuedAt(), invitation.expiresAt(), invitation.issuedBy()));
        row.apply(invitation.consumedAt(), invitation.revokedAt(), invitation.delivery(),
                invitation.deliveryAttemptedAt());
        return toDomain(invitations.saveAndFlush(row));
    }

    private static PortalInvitation toDomain(PortalInvitationJpaEntity row) {
        return new PortalInvitation(row.getId(), row.getUserId(), row.getTokenDigest(), row.getIssuedAt(),
                row.getExpiresAt(), row.getIssuedBy(), row.getConsumedAt(), row.getRevokedAt(),
                row.getDeliveryStatus(), row.getDeliveryAttemptedAt(), row.getVersion());
    }
}
