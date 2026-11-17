package com.stockflow.order.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 09 BR-18): why an order on a vehicle may not have its goods handed over now - the vehicle is then
 * refused whole, with every such order listed.
 *
 * @param reasonCode  the first of {@code reasonCodes}, kept for callers that read one reason; the error code the order's own handover would answer (ORDER_BALANCE_NOT_PAID,
 *                    DELIVERY_BATCH_WINDOW_NOT_OPEN, ORDER_ON_HOLD...)
 * @param reasonCodes every reason the order's goods may not leave now, in the order they are checked (at least one)
 * @param amountDue   what its installments still want before the goods leave (zero when that is not the reason)
 * @param batchSeq    the delivery batch on its way out; null for an order without one
 * @param windowStart the first day that batch may be handed over; null when it has none
 */
public record HandoverBlock(UUID orderId, String orderNumber, String reasonCode, List<String> reasonCodes,
                            BigDecimal amountDue, String currency, Integer batchSeq, LocalDate windowStart) {

    public HandoverBlock {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
