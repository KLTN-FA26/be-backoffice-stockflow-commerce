package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.PermissionJpaEntity;

import java.util.Collection;
import java.util.List;

/** Spring Data repository for {@link PermissionJpaEntity}. */
public interface PermissionJpaRepository extends BaseJpaRepository<PermissionJpaEntity> {

    /** Rows for {@code resource:ACTION} codes, as stored in {@code permission.code}. */
    List<PermissionJpaEntity> findByCodeIn(Collection<String> codes);
}
