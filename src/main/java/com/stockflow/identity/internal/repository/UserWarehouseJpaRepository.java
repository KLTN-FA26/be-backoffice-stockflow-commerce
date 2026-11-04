package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.UserWarehouseJpaEntity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link UserWarehouseJpaEntity}. */
public interface UserWarehouseJpaRepository extends BaseJpaRepository<UserWarehouseJpaEntity> {

    List<UserWarehouseJpaEntity> findByUserId(UUID userId);

    /** The assignments of a page of users in one query, for the user list. */
    List<UserWarehouseJpaEntity> findByUserIdIn(Collection<UUID> userIds);
}
