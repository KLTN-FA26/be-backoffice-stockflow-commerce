package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;

/** Persistence port for {@link User}. See {@code IdentityServiceImpl}'s class javadoc for why the
 *  other RBAC operations in this module go straight to the JPA repositories instead. */
public interface UserRepository extends AggregateRepository<User, UserId> {

    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);

    /**
     * Loads the account under a row lock held to the end of the transaction. Call it before anything
     * else in the transaction reads the account: a copy already in the persistence context would be
     * returned as it was, and the lock would protect stale state.
     */
    Optional<User> findByIdForUpdate(UserId id);
}
