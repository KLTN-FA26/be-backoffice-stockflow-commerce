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
     * Release a paid order (or a deposit order whose deposit is in) from a warehouse: to IN_PRODUCTION
     * when it has print lines, READY_TO_FULFILL otherwise.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND},
     *         {@code DESIGN_SAMPLE_NOT_APPROVED} (BR-PRD-08), {@code ORDER_DEPOSIT_NOT_RECEIVED} (BR-PRD-09),
     *         {@code CONFLICT} (not in a releasable status)
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy);
}
