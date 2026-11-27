package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the refund of a return is approved by a second person (team plan 4D-3;
 * kltn-docs 17 §4.10 step 40, 15 §4.5 step 15): the goods that came back are refunded.
 *
 * <ul>
 *   <li>Money, settled by payment in the same transaction against the order's installments: {@code offsetAmount} was
 *       taken off what they still owed, {@code refundedAmount} was paid already and is to be refunded - the accountant
 *       records the transfer (15 §4.5 step 16). Their sum is what the goods refunded were worth with their VAT.
 *       {@code creditedAmount} is always zero: nothing stays as the customer's credit (15 BR-05).</li>
 *   <li>{@code replacementOrderId} / {@code replacementOrderNumber} are always null: an exchange is a new quote, not
 *       a free order (17 §4.10 step 41).</li>
 *   <li>Each line: {@code acceptedQuantity} the units of its SKU that came back, {@code creditQuantity} the units
 *       refunded and {@code creditAmount} their value before VAT (what the sales rollups take off revenue);
 *       {@code replacementQuantity} is always zero.</li>
 *   <li>{@code vatAdjustmentRequired}: money was given back for invoiced goods, so accounting issues an adjustment
 *       invoice (hóa đơn điều chỉnh giảm, TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c.1, under NĐ 254/2026/NĐ-CP;
 *       21 §4.4).</li>
 *   <li>{@code paidOrderId}: the returned order itself.</li>
 *   <li>{@code shipmentId} (WF-05 TH-1): the delivered shipment the return was against - its e-invoice is the one
 *       adjusted first (17 §4.10 step 40: the invoice of the shipment that carried the goods). Null for a return of an
 *       order delivered without shipments, and in publications stored before.</li>
 *   <li>A return closed with nothing usable come back (WF-05 TH-4) is announced too: every amount zero, no line
 *       refunded, {@code vatAdjustmentRequired} false.</li>
 *   <li>{@code refusalReasonCode} (WF-05 Q-4): why goods that came back are not refunded, or not all of them -
 *       {@code CUSTOMER_DAMAGE}, {@code NOT_DEFECTIVE}, {@code OUT_OF_POLICY} or {@code OTHER}. A code, not the
 *       public note Sales wrote beside it: an event carries facts, and the note stays with the return, behind its
 *       permission check (the portal shows it). Null when everything that came back was refunded, and in publications
 *       stored before.</li>
 *   <li>{@code vatAdjustmentRequired} (WF-05 BE-44) is true exactly when units were refunded, whatever they were worth
 *       in money: units refunded worth nothing on an order with nothing paid were still invoiced.</li>
 * </ul>
 *
 * <p>The shape is the one SCRUM-260 published, when a return could also be replaced or credited; the fields those
 * filled stay, empty (see {@code package-info.java} for the rules on changing this contract).</p>
 */
public record ReturnResolved(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID replacementOrderId,
        String replacementOrderNumber,
        BigDecimal offsetAmount,
        BigDecimal refundedAmount,
        BigDecimal creditedAmount,
        String currency,
        boolean vatAdjustmentRequired,
        List<Line> lines,
        UUID resolvedBy,
        Instant resolvedAt,
        UUID paidOrderId,
        UUID shipmentId,
        String refusalReasonCode
) {

    public ReturnResolved {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** As published before a refund could be refused with a reason (WF-05 Q-4). */
    public ReturnResolved(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          UUID replacementOrderId, String replacementOrderNumber, BigDecimal offsetAmount,
                          BigDecimal refundedAmount, BigDecimal creditedAmount, String currency,
                          boolean vatAdjustmentRequired, List<Line> lines, UUID resolvedBy, Instant resolvedAt,
                          UUID paidOrderId, UUID shipmentId) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, replacementOrderId, replacementOrderNumber,
                offsetAmount, refundedAmount, creditedAmount, currency, vatAdjustmentRequired, lines, resolvedBy,
                resolvedAt, paidOrderId, shipmentId, null);
    }

    /** As published before {@code shipmentId} was added: against the order as a whole. */
    public ReturnResolved(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          UUID replacementOrderId, String replacementOrderNumber, BigDecimal offsetAmount,
                          BigDecimal refundedAmount, BigDecimal creditedAmount, String currency,
                          boolean vatAdjustmentRequired, List<Line> lines, UUID resolvedBy, Instant resolvedAt,
                          UUID paidOrderId) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, replacementOrderId, replacementOrderNumber,
                offsetAmount, refundedAmount, creditedAmount, currency, vatAdjustmentRequired, lines, resolvedBy,
                resolvedAt, paidOrderId, null, null);
    }

    /** As published before {@code paidOrderId} was added: the returned order paid for its goods. */
    public ReturnResolved(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          UUID replacementOrderId, String replacementOrderNumber, BigDecimal offsetAmount,
                          BigDecimal refundedAmount, BigDecimal creditedAmount, String currency,
                          boolean vatAdjustmentRequired, List<Line> lines, UUID resolvedBy, Instant resolvedAt) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, replacementOrderId, replacementOrderNumber,
                offsetAmount, refundedAmount, creditedAmount, currency, vatAdjustmentRequired, lines, resolvedBy,
                resolvedAt, null, null, null);
    }

    /** The order whose money settled this return. */
    public UUID moneyOrderId() {
        return paidOrderId == null ? orderId : paidOrderId;
    }

    /**
     * Of the line's {@code acceptedQuantity} that came back, {@code creditQuantity} refunded, worth {@code creditAmount}
     * before VAT; {@code replacementQuantity} is zero since 4D-3.
     */
    public record Line(UUID orderLineId, String sku, int acceptedQuantity, int replacementQuantity, int creditQuantity,
                       BigDecimal creditAmount) {
    }
}
