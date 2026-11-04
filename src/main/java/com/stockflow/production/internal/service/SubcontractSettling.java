package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.api.SettleSubcontractCommand;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractSettlement;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionRecords;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The end of a subcontracted production order, in the caller's transaction (BR-PRD-16, 17; 04c F-PROD G10): its
 * blanks accounted for, its SUBCONTRACT order closed for production, and the settlement the subcontract invoice is
 * matched against reported to procurement from this module's own records — the goods receipts and their
 * reconciliation, the recalls, the QC rounds and the blanks sent out.
 */
@Component
class SubcontractSettling {

    private final ProductionRecords records;
    private final WorkshopStock stock;
    private final SubcontractPurchasing purchasing;

    SubcontractSettling(ProductionRecords records, WorkshopStock stock, SubcontractPurchasing purchasing) {
        this.records = records;
        this.stock = stock;
        this.purchasing = purchasing;
    }

    /**
     * BR-PRD-16: a SUPPLIED_BLANKS order ends only once every receipt's blanks are reconciled and none is left at the
     * subcontractor — recalled, or written off as lost. Not received yet is not lost (decision Q9): the warehouse
     * says what happened to them.
     */
    void requireBlanksAccountedFor(ProductionOrder order) {
        if (order.mode() != ProductionOrder.Mode.SUBCONTRACTED
                || order.subcontract().method() != ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS) {
            return;
        }
        if (records.subcontractReceiptsOf(order.id()).stream().anyMatch(receipt -> !receipt.reconciled())) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s has a goods receipt whose blanks are not reconciled yet (BR-PRD-16)"
                            .formatted(order.orderNumber()));
        }
        int there = stock.blanksAtSubcontractor(order);
        if (there > 0) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    ("%d blank(s) of production order %s are still at the subcontractor: recall them, returned or lost, "
                            + "first (BR-PRD-16)").formatted(there, order.orderNumber()));
        }
    }

    /**
     * The order ended (completed, cancelled, or replaced in house): a waiting amendment is withdrawn and the order
     * cancelled or closed short if anything was left open, then settled once — QC-passed units paid, failed ones not,
     * blanks lost above the subcontractor's tolerance waiting for the warehouse manager's approval, an agreed
     * cancellation fee added.
     */
    SubcontractSettlement settle(ProductionOrder order, String reason, BigDecimal fee, String feeReason, UUID by) {
        UUID purchaseOrderId = Objects.requireNonNull(order.subcontract().purchaseOrderId(), "purchaseOrderId");
        purchasing.closeForProduction(purchaseOrderId, reason, by);
        List<ProductionRecords.SubcontractReceipt> receipts = records.subcontractReceiptsOf(order.id());
        List<ProductionRecords.QcRound> rounds = records.qcRoundsOf(order.id());
        List<ProductionRecords.SubcontractRecall> recalls = records.recallsOf(order.id());
        boolean suppliedBlanks = order.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS;
        int sent = suppliedBlanks
                ? records.issuesOf(order.id()).stream().mapToInt(ProductionRecords.MaterialIssue::quantity).sum() : 0;
        return purchasing.settle(new SettleSubcontractCommand(purchaseOrderId, order.id(),
                receipts.stream().mapToInt(ProductionRecords.SubcontractReceipt::quantity).sum(),
                rounds.stream().mapToInt(ProductionRecords.QcRound::passed).sum(),
                rounds.stream().mapToInt(ProductionRecords.QcRound::failed).sum(),
                sent,
                receipts.stream().mapToInt(r -> r.usedBlanks() == null ? 0 : r.usedBlanks()).sum(),
                receipts.stream().mapToInt(r -> r.returnedBlanks() == null ? 0 : r.returnedBlanks()).sum()
                        + recalls.stream().mapToInt(ProductionRecords.SubcontractRecall::returned).sum(),
                receipts.stream().mapToInt(r -> r.spoiltBlanks() == null ? 0 : r.spoiltBlanks()).sum()
                        + recalls.stream().mapToInt(ProductionRecords.SubcontractRecall::spoilt).sum(),
                fee, feeReason, by));
    }
}
