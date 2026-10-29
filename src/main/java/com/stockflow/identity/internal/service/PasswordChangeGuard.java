package com.stockflow.identity.internal.service;

import com.stockflow.common.security.PasswordChangeRequirement;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Answers the security chain's per-request question "must this account change its password first".
 * One primary-key read of a single column, uncached on purpose (see {@link PasswordChangeRequirement}).
 */
@Component
class PasswordChangeGuard implements PasswordChangeRequirement {

    private final UserJpaRepository users;

    PasswordChangeGuard(UserJpaRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRequired(UUID userId) {
        return users.findMustChangePassword(userId).orElse(false);
    }
}
