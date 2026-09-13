package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares the "permission matrix" resource — see {@link UsersResourceDeclaration} for why this is
 * a separate marker class rather than a second annotation on {@link IdentityController}.
 */
@PermissionResource(
        code = IdentityResources.RBAC,
        group = "Platform",
        label = "Permission matrix",
        route = "/admin/permissions",
        apiPath = "/api/v1/identity/rbac",
        actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
final class RbacResourceDeclaration {

    private RbacResourceDeclaration() {
    }
}
