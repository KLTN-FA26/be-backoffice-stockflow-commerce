package com.stockflow.identity.internal.service;

import com.stockflow.common.security.UserAccountLookup;
import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.identity.internal.repository.RoleJpaRepository;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import com.stockflow.identity.internal.repository.UserRoleJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link UserAccountLookup}: a user's account as stored now, for {@code RunAs}. Read in a read-only transaction of its
 * own, like {@link WarehouseAssignments}, because it is asked from a scheduler thread where none is open.
 *
 * <p>An account may sign in when it is ACTIVE and not locked until a time still to come: the same two conditions login
 * applies, so a job never acts for someone who could not act for themselves.</p>
 */
@Component
class UserAccounts implements UserAccountLookup {

    private final UserJpaRepository users;
    private final UserRoleJpaRepository userRoles;
    private final RoleJpaRepository roles;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    UserAccounts(UserJpaRepository users, UserRoleJpaRepository userRoles, RoleJpaRepository roles, Clock clock,
                 PlatformTransactionManager transactionManager) {
        this.users = users;
        this.userRoles = userRoles;
        this.roles = roles;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<UserAccount> account(UUID userId) {
        return readOnly.execute(status -> users.findById(userId).map(user -> {
            boolean active = user.getStatus() == UserStatus.ACTIVE
                    && (user.getLockedUntil() == null || !user.getLockedUntil().isAfter(clock.instant()));
            List<UUID> roleIds = userRoles.findByUserId(userId).stream().map(UserRoleJpaEntity::getRoleId).toList();
            List<String> codes = roles.findAllById(roleIds).stream().map(RoleJpaEntity::getCode).sorted().toList();
            return new UserAccount(userId, user.getUsername(), active, codes);
        }));
    }
}
