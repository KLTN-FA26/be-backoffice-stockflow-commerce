package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code customer-sales-owners} (V20270128000140; sales-owner plan BR-S01): READ - who looks after
 * a customer and the history; UPDATE - assign, change, bulk transfer and return, the Sales Manager's alone. Declared
 * here, apart from the endpoints that use it, so the role matrix can grant it before they exist.
 */
@PermissionResource(code = CustomerResources.SALES_OWNERS, group = "Customer", label = "Customer sales owner",
        route = "/admin/sales/assignment", apiPath = "/api/v1/customers/{customerId}/sales-owner", actions = {Action.READ, Action.UPDATE})
final class CustomerSalesOwnersResourceDeclaration {
    private CustomerSalesOwnersResourceDeclaration() { }
}
