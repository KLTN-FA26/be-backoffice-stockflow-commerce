package com.stockflow.procurement.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code procurement-qc-tasks}, the second resource {@link GoodsReceiptController} serves:
 * deciding QC is a different job from counting goods in (docs 03 §2: QC staff, not warehouse staff),
 * so it carries its own permission. A marker class for the same reason as inventory's
 * {@code ReservationsResourceDeclaration}: one {@code @PermissionResource} per type.
 */
@PermissionResource(
        code = PurchaseOrderResources.QC_TASKS,
        group = "Procurement",
        label = "Inbound QC",
        route = "/procurement/goods-receipts",
        apiPath = "/api/v1/goods-receipts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.APPROVE})
final class QcTasksResourceDeclaration {

    private QcTasksResourceDeclaration() {
    }
}
