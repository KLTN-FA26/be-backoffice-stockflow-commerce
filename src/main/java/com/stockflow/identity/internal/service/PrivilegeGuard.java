package com.stockflow.identity.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.PermissionCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "You can only hand out what you hold" (SCRUM-456).
 *
 * <p>Before this, holding {@code identity-users:UPDATE} was enough to give yourself SYSTEM_ADMIN,
 * and holding {@code identity-rbac:APPROVE} (ECOMMERCE_ADMIN, by seed) was enough to tick any
 * permission onto your own role. One rule closes both, and every future variant of them:</p>
 *
 * <ul>
 *   <li>a caller may grant a permission — directly, through a role, or by copying a role — only if
 *       they hold it themselves;</li>
 *   <li>a caller may manage an account (roles, status, password) only if that account holds nothing
 *       the caller lacks. Otherwise resetting an administrator's password would be a takeover;</li>
 *   <li>nobody manages their own account from here: their own password has its own endpoint, and
 *       nobody should lock or demote themselves by a slip of the mouse.</li>
 * </ul>
 *
 * <p>SYSTEM_ADMIN holds every permission, so it passes every check — which is what makes it the
 * only role able to hand out SYSTEM_ADMIN.</p>
 *
 * <p>The caller is read from {@link DataScopeContext}, which {@code RequiresPermissionAspect} fills
 * for every guarded endpoint. With no caller — security switched off, a migration, a test acting
 * as the system — there is nobody to escalate and nothing is checked.</p>
 */
@Component
class PrivilegeGuard {

    private static final int MAX_LISTED = 10;

    private final RoleAuthorizationCache grants;

    PrivilegeGuard(RoleAuthorizationCache grants) {
        this.grants = grants;
    }

    Optional<CurrentUser> actor() {
        return DataScopeContext.current().map(DataScopeContext.Scope::user);
    }

    /** The caller must hold every one of {@code granting}. */
    void requireHolds(Collection<PermissionCode> granting) {
        actor().ifPresent(actor -> {
            Set<String> missing = granting.stream()
                    .filter(code -> !actor.permissions().contains(code))
                    .map(PermissionCode::toString)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!missing.isEmpty()) {
                throw new BusinessException(ErrorCode.PRIVILEGE_ESCALATION,
                        "Caller lacks " + missing.stream().limit(MAX_LISTED).toList()
                                + (missing.size() > MAX_LISTED ? " and " + (missing.size() - MAX_LISTED) + " more" : ""));
            }
        });
    }

    /** The caller must hold everything these roles grant. */
    void requireHoldsRoles(Collection<String> roleCodes) {
        if (actor().isPresent() && !roleCodes.isEmpty()) {
            requireHolds(grants.resolve(roleCodes).permissions());
        }
    }

    /** The caller may manage this account: it is not their own, and it holds nothing they lack. */
    void requireCanManage(UUID targetUserId, Collection<String> targetRoleCodes) {
        actor().ifPresent(actor -> {
            if (actor.userId().equals(targetUserId)) {
                throw new BusinessException(ErrorCode.OWN_ACCOUNT_NOT_MANAGEABLE,
                        "User %s tried to manage their own account".formatted(targetUserId));
            }
            requireHoldsRoles(targetRoleCodes);
        });
    }
}
