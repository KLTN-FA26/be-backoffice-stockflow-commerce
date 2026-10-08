package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.PermissionChecker;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.production.internal.domain.SampleRequest;

/**
 * Uses the shared permission checker and server-resolved principal. Customer ownership is resolved
 * via customer::api, never from a claimed customer id. TEAM/WAREHOUSE staff scopes fail closed until
 * SCRUM-422 supplies their mappings; only ALL or creator OWN can operate in this foundation.
 */
public class SampleAccessPolicy {
    public static final String RESOURCE = "sample-requests";
    private final CurrentUserProvider users;
    private final PermissionChecker permissions;
    private final CustomerService customers;

    public SampleAccessPolicy(CurrentUserProvider users, PermissionChecker permissions, CustomerService customers) {
        this.users = users;
        this.permissions = permissions;
        this.customers = customers;
    }

    public CurrentUser require(Action action, boolean allowCustomer) {
        CurrentUser user = users.current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.userId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        permissions.require(PermissionCode.of(RESOURCE, action));
        if (!allowCustomer && isCustomer(user)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public void requireOwnership(CurrentUser user, SampleRequest sample) {
        if (isCustomer(user)) {
            boolean owns = customers.findByUserId(user.userId())
                    .map(customer -> customer.customerId().equals(sample.customerId())).orElse(false);
            if (!owns) {
                throw new NotFoundException("sample-request", sample.id());
            }
            return;
        }
        DataScope scope = DataScopeContext.current().map(context -> {
            if (!context.user().userId().equals(user.userId())) {
                throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);
            }
            return context.effective();
        }).orElse(user.scope());
        if (user.scope() == null || scope == null) {
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);
        }
        if (scope.isBroaderThan(user.scope())) {
            scope = user.scope();
        }
        if (scope != DataScope.ALL && !(scope == DataScope.OWN && sample.createdBy().equals(user.userId()))) {
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);
        }
    }

    private boolean isCustomer(CurrentUser user) {
        return user.roles().contains(Role.CUSTOMER) && !user.roles().contains(Role.SYSTEM_ADMIN);
    }
}
