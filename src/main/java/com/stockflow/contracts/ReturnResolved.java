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
        UUID paidOrderId
) {

    public ReturnResolved {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** As published before {@code paidOrderId} was added: the returned order paid for its goods. */
    public ReturnResolved(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                          UUID replacementOrderId, String replacementOrderNumber, BigDecimal offsetAmount,
                          BigDecimal refundedAmount, BigDecimal creditedAmount, String currency,
                          boolean vatAdjustmentRequired, List<Line> lines, UUID resolvedBy, Instant resolvedAt) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, replacementOrderId, replacementOrderNumber,
                offsetAmount, refundedAmount, creditedAmount, currency, vatAdjustmentRequired, lines, resolvedBy,
                resolvedAt, null);
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
