package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.User;
import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.entity.UserJpaEntity;

/**
 * Translates between the {@code User} aggregate and its row.
 *
 * <p>Hand-written rather than MapStruct, same reasoning as {@code ProductPersistenceMapper}:
 * rehydration goes through the aggregate's constructor so its invariants are re-checked.</p>
 */
final class UserPersistenceMapper {

    private UserPersistenceMapper() {
    }

    static User toDomain(UserJpaEntity entity) {
        return new User(
                new UserId(entity.getId()),
                entity.getUsername(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getFullName(),
                entity.getStatus(),
                entity.getLastLoginAt(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getCreatedBy());
    }

    static UserJpaEntity toNewEntity(User user) {
        return new UserJpaEntity(user.id().value(), user.username(), user.email(), user.passwordHash(),
                user.fullName(), user.status(), user.lastLoginAt());
    }

    /** Copies the aggregate's mutable state onto a row already managed by the persistence context. */
    static void applyToEntity(User user, UserJpaEntity entity) {
        entity.recordLogin(user.lastLoginAt());
        entity.replacePasswordHash(user.passwordHash());
    }
}
