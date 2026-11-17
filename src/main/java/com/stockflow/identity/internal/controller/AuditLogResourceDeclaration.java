package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares the "audit log" resource - see {@link UsersResourceDeclaration} for why a marker class. Read only: an audit
 * entry is never changed or deleted by hand (only the retention purge removes one), so there is no other action.
 */
@PermissionResource(
        code = IdentityResources.AUDIT_LOGS,
        group = "Platform",
        label = "Audit log",
        route = "/admin/audit-logs",
        apiPath = "/api/v1/audit-logs",
        actions = {Action.VIEW_PAGE, Action.READ})
final class AuditLogResourceDeclaration {

    private AuditLogResourceDeclaration() {
    }
}
