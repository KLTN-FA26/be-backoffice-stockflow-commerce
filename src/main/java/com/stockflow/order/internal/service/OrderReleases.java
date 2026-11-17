package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;

import java.util.UUID;

/**
 * Release of an order by the warehouse manager of the warehouse (SCRUM-423, 17 BR-03). Not on {@code order :: api}: only this
 * module's controller drives it; other modules learn of it from {@code OrderReleased}.
 */
public interface OrderReleases {

    /**
     * Release a CONFIRMED order (confirmed by its contract's installments on signing) - or an order of a contract
     * AWAITING_STOCK whose stock is now all held (4A-6) - from a warehouse: to READY_TO_FULFILL, every line allocated on
     * bins of that warehouse.
     *
     * @throws com.stockflow.common.error.BusinessException {@code WAREHOUSE_NOT_FOUND},
     *         {@code ORDER_PRINT_GOODS_NOT_RECEIVED} (a legacy print line - a blank with a design snapshot - whose goods
     *         were never received; QD-16), {@code ORDER_STOCK_NOT_HELD},
     *         {@code CONFLICT} (not in a releasable status)
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID releasedBy);

    /**
     * 4B-8 (17 §4.5, BR-03, BR-29): the same for an order with a signed delivery schedule - its next delivery batch.
     *
     * @param deliveryBatchId the batch the caller means to release; null: the next one, whichever it is
     * @throws com.stockflow.common.error.BusinessException {@code DELIVERY_BATCH_NOT_NEXT} for another batch, or while
     *         the batch before the next still has goods to hand over; {@code ORDER_STOCK_NOT_HELD} for a batch not held
     *         in full
     */
    OrderSummary release(UUID orderId, UUID warehouseId, UUID deliveryBatchId, UUID releasedBy);

    /**
     * What the person about to release the order should know, never a reason to refuse it (WF4-OVERDUE-WARN): the
     * customer has an installment overdue (15 §4.3 step 9, 11), as {@code creditWarning} warns when a contract is sent
     * (15 BR-11). Read when the release dialog opens; the release itself proceeds whatever it says.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (no such order)
     */
    ReleaseWarnings releaseWarnings(UUID orderId);

    /** @param outcome OVERDUE: the customer owes something overdue */
    record CreditWarning(String outcome) {
        public static final String OVERDUE = "OVERDUE";
    }

    /** @param creditWarning set only when the customer has something overdue */
    record ReleaseWarnings(CreditWarning creditWarning) { }
}
