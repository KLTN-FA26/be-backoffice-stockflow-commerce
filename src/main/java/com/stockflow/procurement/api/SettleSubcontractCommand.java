package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * What production reports when a subcontracted production order ends (BR-PRD-16, 17; 04c F-PROD G10): the basis
 * its SUBCONTRACT order is paid on. Procurement prices it at the order's unit price and applies the subcontractor's
 * loss tolerance.
 *
 * @param receivedQuantity units the subcontractor delivered (all goods receipts)
 * @param passedQuantity   of them, units that passed the production QC: the ones paid for
 * @param failedQuantity   units that failed it: not paid (BR-PRD-17)
 * @param blanksSent       SUPPLIED_BLANKS: blanks sent to the subcontractor in all; 0 for FULL_SERVICE
 * @param blanksUsed       blanks the delivered goods were printed on
 * @param blanksReturned   blanks that came back unused (reconciliation or recall)
 * @param blanksLost       blanks spoilt at the subcontractor (reconciliation or recall), written off as
 *                         SUBCONTRACT_LOSS
 * @param cancellationFee  a fee agreed with the subcontractor for work cancelled after it was sent out, in the
 *                         order's currency; zero for none
 * @param cancellationFeeReason why, required with a fee
 */
public record SettleSubcontractCommand(
        UUID purchaseOrderId,
        UUID productionOrderId,
        int receivedQuantity,
        int passedQuantity,
        int failedQuantity,
        int blanksSent,
        int blanksUsed,
        int blanksReturned,
        int blanksLost,
        BigDecimal cancellationFee,
        String cancellationFeeReason,
        UUID settledBy
) {
    public SettleSubcontractCommand {
        Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        cancellationFee = cancellationFee == null ? BigDecimal.ZERO : cancellationFee;
    }
}
