package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.User;
import com.stockflow.identity.internal.domain.UserId;
import com.stockflow.identity.internal.domain.UserRepository;
import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapts {@link UserRepository} onto {@link UserJpaRepository}, same shape as
 *  {@code ProductRepositoryAdapter} — look up, apply, save. */
@Repository
class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository users;

    UserRepositoryAdapter(UserJpaRepository users) {
        this.users = users;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return users.findById(id.value()).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return users.findByUsername(username).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(UserId id) {
        return users.existsById(id.value());
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity = users.findById(user.id().value())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND,
                        "No user with id " + user.id()));
        UserPersistenceMapper.applyToEntity(user, entity);
        return UserPersistenceMapper.toDomain(users.save(entity));
    }
}
