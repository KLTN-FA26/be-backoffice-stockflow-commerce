package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;

import java.util.UUID;

/**
 * Release of an order by the Order Coordinator (SCRUM-423). Not on {@code order :: api}: only this
 * module's controller drives it; other modules learn of it from {@code OrderReleased}.
 */
public interface OrderReleases {

    /**
     * Release a CONFIRMED order (paid in full, or a deposit order whose deposit is in) - or an order of a contract
     * AWAITING_STOCK whose stock is now all held (4A-6) - from a warehouse: to READY_TO_FULFILL, every line allocated on
     * bins of that warehouse.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND},
     *         {@code ORDER_PRINT_GOODS_NOT_RECEIVED} (a legacy print line - a blank with a design snapshot - whose goods
     *         were never received; QD-16), {@code ORDER_STOCK_NOT_HELD}, {@code ORDER_DEPOSIT_NOT_RECEIVED},
     *         {@code CONFLICT} (not in a releasable status)
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy);
}
