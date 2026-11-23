package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-rma-custody} (WF-05 Q-11; audit round 2, M4): CREATE records goods kept in custody for the
 * customer on a return request - a photo, the SKU or LPN, how many, a note. The warehouse receives the goods at the dock
 * and is told by the receipt ({@code RETURN_CARTON_NOT_CLAIMED}) to record custody, so it must be able to; asking for
 * {@code sales-rmas:CREATE} - opening returns, the customer's and Sales' - refused every warehouse role. A permission of
 * its own grants exactly that write (V20261223000330).
 *
 * <p>A marker, not a bean: {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.RMA_CUSTODY,
        group = "Sales",
        label = "Record goods kept in custody for the customer on a return",
        route = "/sales/returns",
        apiPath = "/api/v1/returns/{returnId}/custody",
        actions = {Action.CREATE})
final class ReturnCustodyResourceDeclaration {

    private ReturnCustodyResourceDeclaration() {
    }
}
