package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delivery failure case (WF-05 fix plan, sections 2-3): a shipment the carrier brought back, received on a return
 * receipt. Since RTM-RETURNED and WF-05 Q-7 it is a record of what came back, closed by the system when the receipt
 * settled the shipment ({@code closure} RECORDED_ON_RETURN); only an order from before RTM-RETURNED, still owing the
 * shipment's units, gets an OPEN case for Sales to decide (WF-05 BE-16).
 *
 * <p>The people, the customer and the warehouse carry their names beside their ids, looked up once per answer, so the
 * screen needs no identity, customer or warehouse permission to say who and where.</p>
 */
@Schema(name = "DeliveryFailureCase")
public record DeliveryFailureCaseResponse(
        UUID caseId,
        UUID orderId,
        @Schema(example = "SO-20261108-000007") String orderNumber,
        UUID customerId,
        @Schema(description = "The order's status now: RETURNED or PARTIALLY_FULFILLED after a carrier's return, on to "
                + "REFUNDED / PARTIALLY_REFUNDED; ON_HOLD only for an order held before RTM-RETURNED") String orderStatus,
        @Schema(description = "The shipment that came back") UUID shipmentId,
        @Schema(description = "The delivery batch it carried; null for an order without a delivery schedule",
                nullable = true) UUID deliveryBatchId,
        UUID receiptId,
        @Schema(description = "The return receipt the goods came back on") String receiptNumber,
        @Schema(description = "The warehouse they came back to") UUID warehouseId,
        @Schema(allowableValues = {"CUSTOMER_ABSENT", "REFUSED", "WRONG_ADDRESS", "LOST", "DAMAGED", "OTHER"})
        String failureReason,
        @Schema(nullable = true) String failureNote,
        @Schema(description = "REFUSED or WRONG_ADDRESS: delivering again charges the failed trip to the customer")
        boolean customerPaysFailedTrip,
        @Schema(description = "OPEN: waits for Sales (orders from before RTM-RETURNED only). CANCELLED: closed - see "
                + "closure. REDELIVERY / REDELIVERED: decided to deliver again before RTM-RETURNED removed that",
                allowableValues = {"OPEN", "REDELIVERY", "REDELIVERED", "CANCELLED"}) String status,
        @Schema(description = "WF-05 F-4: how a CANCELLED case was closed - RECORDED_ON_RETURN (by the system: the return "
                + "receipt settled the shipment, nothing was owed, the case only records what came back) or "
                + "CANCELLED_BY_SALES (Sales cancelled what was not delivered); null while it is not CANCELLED",
                allowableValues = {"RECORDED_ON_RETURN", "CANCELLED_BY_SALES"}, nullable = true) String closure,
        Instant openedAt,
        @Schema(nullable = true) UUID decidedBy,
        @Schema(nullable = true) Instant decidedAt,
        @Schema(description = "What Sales wrote when they decided; null for a case the system closed (closure "
                + "RECORDED_ON_RETURN) - show the closure's label instead", nullable = true) String decisionNote,
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
        long version,
        @Schema(description = "Who decided the case: full name, else username; null when decidedBy is null or unknown",
                nullable = true) String decidedByName,
        @Schema(description = "Who released the redelivery; null when releasedBy is null or unknown", nullable = true)
        String releasedByName,
        @Schema(description = "The customer's registered name; null when the customer record is gone", nullable = true)
        String customerName,
        @Schema(description = "The code the customer is known by; set where customerName is", nullable = true)
        String customerCode,
        @Schema(description = "The code (prefix) of the warehouse the goods came back to", example = "HCM",
                nullable = true) String warehouseCode,
        @Schema(description = "That warehouse's name", nullable = true) String warehouseName) {

    /** The units of an order line that came back, and how many are held for the order again. */
    public record Line(UUID orderLineId, int units, int heldUnits) {
    }

    /** A carton received back, with the condition the warehouse noted. */
    public record Carton(String lpn, String sku, @Schema(nullable = true) String lotNumber,
                         @Schema(description = "The units counted inside it when it came back (WF-05 Q-18) - fewer "
                                 + "than its label when it was opened, 0 when empty") int quantity,
                         @Schema(description = "Dented, wet, opened...; null when nothing was noted. Starts with "
                                 + "NOT_STOCKED when the carton came back opened and was not put into stock (Q-18)",
                                 nullable = true)
                         String condition) {
    }

    /** F-4: the closure of a CANCELLED case the system made, recorded with the note {@code systemNote}. */
    public static String closureOf(String status, String decisionNote, String systemNote) {
        if (!"CANCELLED".equals(status)) {
            return null;
        }
        return systemNote.equals(decisionNote) ? "RECORDED_ON_RETURN" : "CANCELLED_BY_SALES";
    }
}
