package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for {@link RoleJpaEntity}. */
public interface RoleJpaRepository extends BaseJpaRepository<RoleJpaEntity> {

    Optional<RoleJpaEntity> findByCode(String code);

    List<RoleJpaEntity> findAllByOrderByCodeAsc();
}
