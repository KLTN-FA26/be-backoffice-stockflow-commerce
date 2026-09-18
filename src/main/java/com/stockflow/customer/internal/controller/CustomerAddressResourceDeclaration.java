package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

@PermissionResource(code = CustomerResources.ADDRESSES, group = "Customer", label = "Addresses",
        route = "/account/addresses", apiPath = "/api/v1/customers/{customerId}/addresses",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
public final class CustomerAddressResourceDeclaration {
    private CustomerAddressResourceDeclaration() {
    }
}
