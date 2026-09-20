package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;

/** Persistence port for {@link User}. See {@code IdentityServiceImpl}'s class javadoc for why the
 *  other RBAC operations in this module go straight to the JPA repositories instead. */
public interface UserRepository extends AggregateRepository<User, UserId> {

    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
}
