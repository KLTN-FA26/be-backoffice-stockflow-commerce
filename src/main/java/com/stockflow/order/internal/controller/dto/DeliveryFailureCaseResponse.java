package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delivery failure case (WF-05 fix plan, sections 2-3): a shipment the carrier brought back, received on a return
 * receipt, and what Sales decided of it.
 */
@Schema(name = "DeliveryFailureCase")
public record DeliveryFailureCaseResponse(
        UUID caseId,
        UUID orderId,
        @Schema(example = "SO-20261108-000007") String orderNumber,
        UUID customerId,
        @Schema(description = "ON_HOLD while a case of the order waits for Sales; PARTIALLY_FULFILLED once the goods "
                + "are to be delivered again") String orderStatus,
        @Schema(description = "The shipment that came back") UUID shipmentId,
        @Schema(description = "The delivery batch it carried; null for an order without a delivery schedule",
                nullable = true) UUID deliveryBatchId,
        UUID receiptId,
        @Schema(description = "The return receipt the goods came back on") String receiptNumber,
        @Schema(description = "The warehouse they came back to, and are delivered again from") UUID warehouseId,
        @Schema(allowableValues = {"CUSTOMER_ABSENT", "REFUSED", "WRONG_ADDRESS", "LOST", "DAMAGED", "OTHER"})
        String failureReason,
        @Schema(nullable = true) String failureNote,
        @Schema(description = "REFUSED or WRONG_ADDRESS: delivering again charges the failed trip to the customer")
        boolean customerPaysFailedTrip,
        @Schema(description = "OPEN: waits for Sales. REDELIVERY: to be delivered again - held, released, picked. "
                + "REDELIVERED: handed over again. CANCELLED: what was not delivered was cancelled",
                allowableValues = {"OPEN", "REDELIVERY", "REDELIVERED", "CANCELLED"}) String status,
        Instant openedAt,
        @Schema(nullable = true) UUID decidedBy,
        @Schema(nullable = true) Instant decidedAt,
        @Schema(nullable = true) String decisionNote,
        @Schema(description = "What the failed trip was charged at; null when nothing was", nullable = true)
        BigDecimal failedTripFee,
        @Schema(description = "The fee item (installment) owing it", nullable = true) UUID feeInstallmentId,
        @Schema(description = "Nothing charged: the cost is claimed from the carrier, outside the system")
        boolean carrierClaim,
        @Schema(description = "Decided to deliver again, every unit held at the warehouse, not released yet: the "
                + "warehouse manager may release the order") boolean readyForRelease,
        @Schema(nullable = true) Instant releasedAt,
        @Schema(nullable = true) UUID releasedBy,
        @Schema(description = "The shipment that delivered the goods again", nullable = true) UUID redeliveryShipmentId,
        @Schema(nullable = true) Instant redeliveredAt,
        @Schema(nullable = true) Instant closedAt,
        List<Line> lines,
        List<Carton> cartons,
        long version) {

    /** The units of an order line that came back, and how many are held for the order again. */
    public record Line(UUID orderLineId, int units, int heldUnits) {
    }

    /** A carton received back, with the condition the warehouse noted. */
    public record Carton(String lpn, String sku, @Schema(nullable = true) String lotNumber, int quantity,
                         @Schema(description = "Dented, wet, opened...; null when nothing was noted", nullable = true)
                         String condition) {
    }
}
