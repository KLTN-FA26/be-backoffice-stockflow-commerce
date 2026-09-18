package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.Optional;

/** Spring Data repository for {@link UserJpaEntity}. */
public interface UserJpaRepository extends BaseJpaRepository<UserJpaEntity> {

    Optional<UserJpaEntity> findByUsernameIgnoreCase(String username);
    Optional<UserJpaEntity> findByEmailIgnoreCase(String email);
}
