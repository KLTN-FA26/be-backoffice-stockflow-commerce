package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserRoleJpaEntity}. */
public interface UserRoleJpaRepository extends BaseJpaRepository<UserRoleJpaEntity> {

    Optional<UserRoleJpaEntity> findByUserIdAndRoleId(UUID userId, UUID roleId);
}
