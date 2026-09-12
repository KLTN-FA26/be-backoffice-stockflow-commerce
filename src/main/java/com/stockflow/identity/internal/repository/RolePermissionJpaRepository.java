package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.entity.RolePermissionJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link RolePermissionJpaEntity}. */
public interface RolePermissionJpaRepository extends BaseJpaRepository<RolePermissionJpaEntity> {

    List<RolePermissionJpaEntity> findByRoleId(UUID roleId);
}
