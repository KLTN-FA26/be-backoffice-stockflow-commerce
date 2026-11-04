package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.PortalInvitationJpaEntity;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link PortalInvitationJpaEntity}. */
interface PortalInvitationJpaRepository extends BaseJpaRepository<PortalInvitationJpaEntity> {

    Optional<PortalInvitationJpaEntity> findByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(UUID userId);
}
