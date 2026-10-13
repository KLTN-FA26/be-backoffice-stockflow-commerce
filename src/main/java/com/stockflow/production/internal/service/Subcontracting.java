package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Part of an ORDER printed by an outside print shop (SCRUM-433/441; 03 §16; 04c F-PROD "Nhánh gia công ngoài").
 */
public interface Subcontracting {

    /**
     * @param quantity     units taken from the parent's unissued quantity (BR-PRD-12)
     * @param subcontractorId a supplier flagged as a print subcontractor (BR-PRD-13)
     * @param unitPrice    the price of the print work per good unit
     * @param dueDate      when the subcontractor delivers
     */
    record Split(int quantity, UUID subcontractorId, ProductionOrder.SubcontractMethod method, BigDecimal unitPrice,
                 String currency, LocalDate dueDate) {
    }

    /**
     * G1–G3: the parent keeps its status and loses {@code quantity}; the child (READY if the parent passed
     * prepress, else PENDING_PREPRESS) gets its DRAFT SUBCONTRACT PO; the order's blanks for that quantity are
     * let go (FULL_SERVICE) or held for the child instead (SUPPLIED_BLANKS) in the same transaction.
     *
     * @return the child
     */
    ProductionOrders.Detail split(CurrentUser user, UUID parentId, Split split);

    /**
     * G5–G6: READY → SUBCONTRACTED, only once the child's SUBCONTRACT PO is approved (BR-PRD-13). SUPPLIED_BLANKS:
     * the child's blanks go with it to the subcontractor's location for its warehouse (decision Q7) — after a
     * shortfall (G9 "gửi bù"), what is still missing is held first.
     */
    ProductionOrders.Detail sendOut(CurrentUser user, UUID childId);

    /**
     * Decision Q9, one goods receipt of a SUPPLIED_BLANKS child reconciled by the warehouse: {@code spoilt} blanks
     * are written off (SUBCONTRACT_LOSS, the adjustment approval), {@code returned} come back free to
     * {@code returnLocation} (empty: where each was sent from); the blanks the received goods were printed on left
     * with the receipt. The rest stays at the subcontractor. Once per receipt; the same answer again is a replay.
     */
    ProductionOrders.Detail reconcile(CurrentUser user, UUID childId, UUID receiptId, int spoilt, int returned,
                                      String returnLocation);

    /** The child's goods receipts with their reconciliation. */
    java.util.List<com.stockflow.production.internal.domain.ProductionRecords.SubcontractReceipt> receipts(
            CurrentUser user, UUID childId);
}
