package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code sales-owner-change-requests} (V20270128000140; sales-owner plan §10.5): CREATE - a portal
 * customer asks; READ - the customer their own, a manager all; APPROVE - the manager decides.
 */
@PermissionResource(code = CustomerResources.OWNER_CHANGE_REQUESTS, group = "Customer", label = "Requests to change salesperson",
        route = "/admin/sales/owner-change-requests", apiPath = "/api/v1/sales-owner-change-requests", actions = {Action.CREATE, Action.READ, Action.APPROVE})
final class SalesOwnerChangeRequestsResourceDeclaration {
    private SalesOwnerChangeRequestsResourceDeclaration() { }
}
