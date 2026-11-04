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
     * <p>BR-PRD-12: {@code quantity} equal to the whole plan of an order with nothing issued or printed converts the
     * order itself instead — same row and number, subcontracted, its own PO, its blanks treated the same way.</p>
     *
     * @return the child, or the converted order
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

    /**
     * Blanks brought back from the subcontractor without a goods receipt (BR-PRD-15: they leave its location only
     * through a receipt or a reasoned adjustment): {@code returned} back to stock, to {@code returnLocation} or where
     * each was sent from; {@code spoilt} written off as SUBCONTRACT_LOSS through the adjustment approval. Idempotent
     * on {@code requestId}.
     */
    record Recall(UUID requestId, int returned, int spoilt, String returnLocation, String note) {
    }

    ProductionOrders.Detail recall(CurrentUser user, UUID childId, Recall recall);

    /** The recalls of a subcontracted order. */
    java.util.List<com.stockflow.production.internal.domain.ProductionRecords.SubcontractRecall> recalls(
            CurrentUser user, UUID childId);

    /**
     * 03 §16 G9 "in bù nội bộ": {@code quantity} of what the subcontractor still owes becomes a new in-house order of
     * the same line (READY with the inherited prepress, its blanks held now, or ON_HOLD BLANKS_SHORT when the
     * warehouse has too few). The subcontracted order's plan shrinks by it; if it owed nothing more and has no QC
     * waiting it is COMPLETED and handed over to {@code packingLocation}; if it gave everything back it is CANCELLED
     * and replaced. Either end closes its PO for production and settles it (its blanks must be accounted for first).
     *
     * @return the new in-house order
     */
    ProductionOrders.Detail takeInHouse(CurrentUser user, UUID childId, int quantity, String reason,
                                        String packingLocation);

    /**
     * 03 §16 SUBCONTRACTED → CANCELLED (or from PENDING_PREPRESS / READY): while the sales order still wants the goods
     * (decision QA2), what the subcontractor still owes goes to a new in-house order ({@link #takeInHouse}) and the
     * good units it delivered stay the order's — none: CANCELLED and replaced; some: COMPLETED with them; units still
     * waiting for QC are inspected first. Once the sales order is cancelled, good units it has are written off, sold to
     * nobody else. Its blanks must be recalled first; its PO is cancelled (or closed short) and settled,
     * with {@code fee} — an amount agreed with the subcontractor for work done, with its reason — added.
     */
    ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason, BigDecimal fee,
                                   String feeReason, String packingLocation);

    /**
     * 03 §16 G9 "gửi bù": {@code quantity} more on the SUBCONTRACT PO for a resend after a QC shortfall. A draft PO is
     * resized; an approved one gets an amendment the PO approver approves (four eyes) before the work goes out again.
     */
    ProductionOrders.Detail supplement(CurrentUser user, UUID childId, int quantity, String reason);

    /** BR-PRD-17 / G10: the settlement of the order's SUBCONTRACT PO, once the order ended. */
    com.stockflow.procurement.api.SubcontractSettlement settlement(CurrentUser user, UUID productionOrderId);

    /**
     * BR-PRD-17: the warehouse manager approves blanks lost above the subcontractor's tolerance, with the deduction per
     * blank agreed with the subcontractor.
     */
    com.stockflow.procurement.api.SubcontractSettlement approveLoss(CurrentUser user, UUID productionOrderId,
                                                                    BigDecimal deductionPerBlank, String note);

    /** One lot of blanks sent to the subcontractor. */
    record SentBlanks(String sku, String lotNumber, String fromLocation, String toLocation, int quantity,
                      java.time.Instant sentAt) {
    }

    /**
     * BR-PRD-14, 15: what goes out with the work - the SUBCONTRACT PO, the subcontractor, the prepress-checked
     * PRINT_READY file (a fresh download link), the approved sample QC will compare against, and the blanks sent.
     */
    record DispatchNote(UUID productionOrderId, String orderNumber, ProductionOrder.Status status, String method,
                        UUID purchaseOrderId, String purchaseOrderNumber, String purchaseOrderStatus,
                        UUID subcontractorId, String subcontractorCode, String subcontractorName, String blankSku,
                        int quantity, LocalDate dueDate, LocalDate expectedDate, UUID designSnapshotId,
                        String designChecksum, ProductionWorkflow.PrintFile printFile, UUID approvedSampleId,
                        java.time.Instant prepressCheckedAt, java.util.List<SentBlanks> blanks) {
    }

    DispatchNote dispatchNote(CurrentUser user, UUID childId);
}
