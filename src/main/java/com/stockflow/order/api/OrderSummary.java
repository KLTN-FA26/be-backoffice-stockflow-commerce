package com.stockflow.order.api;

import com.stockflow.common.domain.Money;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of an order, for other modules and for the API.
 *
 * <p>Flat and immutable. Handing out the {@code Order} aggregate would let a caller invoke
 * {@code confirmPayment()} on it outside a transaction, which is exactly the kind of bypass the
 * module boundary exists to prevent.</p>
 */
public record OrderSummary(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        OrderStatus status,
        Money total,
        List<LineSummary> lines,
        Instant placedAt
) {

    /**
     * @param reservationIds every hold inventory gave back for this line — one per lot it was drawn
     *                       from — kept so cancellation knows what to release
     */
    public record LineSummary(
            UUID lineId,
            String sku,
            int quantity,
            Money unitPrice,
            Money lineTotal,
            List<UUID> reservationIds,
            UUID designSnapshotId,
            String designChecksum
    ) {

        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice,
                           Money lineTotal, List<UUID> reservationIds) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, null, null);
        }

        public LineSummary {
            reservationIds = reservationIds == null ? List.of() : List.copyOf(reservationIds);
        }
    }
}
