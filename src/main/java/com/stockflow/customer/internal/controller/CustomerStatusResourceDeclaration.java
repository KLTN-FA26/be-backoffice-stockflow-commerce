package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code customer-status} (V20261021000600): activating, suspending and closing a customer
 * account, {@code POST /api/v1/customers/{customerId}/status}. Its own resource because {@code
 * customer-customers:APPROVE} is also held by Sales, who do not change a customer's status.
 */
@PermissionResource(code = CustomerResources.STATUS, group = "Customer", label = "Customer status (activate, suspend)",
        route = "/admin/customers", apiPath = "/api/v1/customers/{customerId}/status", actions = {Action.APPROVE})
final class CustomerStatusResourceDeclaration {
    private CustomerStatusResourceDeclaration() { }
}
