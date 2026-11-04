package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;

import java.util.UUID;

/**
 * Release of an order by the Order Coordinator (SCRUM-423). Not on {@code order :: api}: only this
 * module's controller drives it; other modules learn of it from {@code OrderLinesReleasedForProduction}
 * and {@code OrderReleased}.
 */
public interface OrderReleases {

    /**
     * Release a CONFIRMED order (paid in full, or a deposit order whose deposit is in) from a warehouse: to IN_PRODUCTION
     * when it has print lines, READY_TO_FULFILL otherwise.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND},
     *         {@code DESIGN_SAMPLE_NOT_APPROVED} (BR-PRD-08), {@code ORDER_DEPOSIT_NOT_RECEIVED} (BR-PRD-09),
     *         {@code CONFLICT} (not in a releasable status)
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy);

    /**
     * As {@link #release(UUID, UUID, UUID)}, choosing how the order travels on the way (LP-1, lead's decision
     * 2026-10-22): null keeps the order's mode (PARCEL unless changed before). A mode other than the order's - read
     * under the order's row lock, so a change racing the release is seen - needs
     * {@code sales-order-delivery-modes:UPDATE} as well; the change is recorded in the order's history.
     *
     * @throws com.stockflow.common.security.PermissionDeniedException a mode other than the order's without that
     *         permission: nothing is released
     * @throws com.stockflow.common.error.BusinessException also {@code DELIVERY_MODE_FTL_NO_COD} (LP-2): nothing is
     *         released then
     */
    OrderSummary release(UUID orderId, UUID warehouseId, com.stockflow.order.api.DeliveryMode deliveryMode,
                         com.stockflow.common.security.CurrentUser actor);
}
