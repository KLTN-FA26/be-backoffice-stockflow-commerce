package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A SUBCONTRACT order's settlement (BR-PRD-17, 04c F-PROD G10): the amounts its invoice is matched against.
 * {@code payableAmount} = units that passed QC x unit price - units that failed QC are not paid; blanks lost above
 * {@code toleranceBlanks} ({@code excessLoss}) are deducted at {@code deductionPerBlank} once the warehouse manager
 * approved them; {@code netPayable} = payable - deduction + cancellation fee.
 *
 * @param status AWAITING_APPROVAL while a loss above tolerance waits for the warehouse manager, else FINAL
 */
public record SubcontractSettlement(
        UUID purchaseOrderId,
        UUID productionOrderId,
        String status,
        String currency,
        BigDecimal unitPrice,
        int receivedQuantity,
        int passedQuantity,
        int failedQuantity,
        BigDecimal payableAmount,
        int blanksSent,
        int blanksUsed,
        int blanksReturned,
        int blanksLost,
        BigDecimal tolerancePercent,
        int toleranceBlanks,
        int excessLoss,
        BigDecimal deductionPerBlank,
        BigDecimal deductionAmount,
        BigDecimal cancellationFee,
        String cancellationFeeReason,
        BigDecimal netPayable,
        Instant settledAt,
        UUID settledBy,
        Instant approvedAt,
        UUID approvedBy,
        String approvalNote
) {
}
