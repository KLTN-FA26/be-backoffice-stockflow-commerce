package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;

import java.util.UUID;

/**
 * Release of an order by the Order Coordinator (SCRUM-423). Not on {@code order :: api}: only this
 * module's controller drives it; other modules learn of it from {@code OrderReleased}.
 */
public interface OrderReleases {

    /**
     * Release a CONFIRMED order (paid in full, or a deposit order whose deposit is in) from a warehouse: to
     * READY_TO_FULFILL, every line allocated on bins of that warehouse.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND},
     *         {@code ORDER_PRINT_GOODS_NOT_RECEIVED} (a print line: its goods are not received yet),
     *         {@code ORDER_DEPOSIT_NOT_RECEIVED}, {@code CONFLICT} (not in a releasable status)
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy);
}
