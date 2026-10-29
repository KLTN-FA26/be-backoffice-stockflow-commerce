package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a return is resolved (03 §12): every accepted unit is made good, by new
 * goods or by money.
 *
 * <ul>
 *   <li>{@code replacementOrderId}: the free order the replacements ship as (released and fulfilled as any order);
 *       null when nothing is replaced.</li>
 *   <li>Money, booked by payment in the same transaction: {@code offsetAmount} reduced the order's open receivables,
 *       {@code refundedAmount} waits as PENDING refunds for the accountant, {@code creditedAmount} stays as the
 *       customer's credit. Their sum is the value of the accepted units not replaced, at the order's prices.</li>
 *   <li>{@code vatAdjustmentRequired}: money was given back for invoiced goods, so accounting issues an adjustment
 *       invoice (hóa đơn điều chỉnh giảm, Nghị định 123/2020/NĐ-CP, Thông tư 78/2021/TT-BTC) - the system has no
 *       e-invoicing and only says so.</li>
 *   <li>{@code paidOrderId}: the order the goods were paid on, whose money settled it - the returned order itself, or
 *       for a return on a free replacement order the order whose units it replaced. Null in events published before
 *       it was added: read it as {@code orderId}.</li>
 * </ul>
 *
 * See {@code package-info.java} for the rules on changing this contract.
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

    /** Of the line's {@code acceptedQuantity}, {@code replacementQuantity} replaced and {@code creditQuantity} credited. */
    public record Line(UUID orderLineId, String sku, int acceptedQuantity, int replacementQuantity, int creditQuantity,
                       BigDecimal creditAmount) {
    }
}
