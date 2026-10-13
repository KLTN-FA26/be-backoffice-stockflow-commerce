package com.stockflow.procurement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SUBCONTRACT purchase orders for production subcontracting (SCRUM-434; kltn-docs 19-production §4.4,
 * 02-purchase-order BR-10). Called by the <b>production</b> module when the warehouse manager splits
 * part of an ORDER production order off to an outside print shop.
 *
 * <p>A SUBCONTRACT PO belongs to exactly one SUBCONTRACTED production order and carries one line: the
 * finished item, the quantity, the unit price of the print work. Its quantity changes only through
 * {@link #supplement}. Otherwise it is a purchase order like any other — submitted, approved, received
 * (SCRUM-435) and matched against the invoice the same way.</p>
 *
 * <p>On the new purchase-order tables ({@code procurement.purchase_orders}), where goods receipts are.</p>
 */
public interface SubcontractPurchasing {

    /**
     * Raise the DRAFT SUBCONTRACT PO for a production order. Idempotent per production order: asking
     * again returns the live PO already raised (a cancelled one may be replaced).
     *
     * @throws com.stockflow.common.error.BusinessException {@code SUPPLIER_NOT_FOUND},
     *         {@code SUPPLIER_NOT_SUBCONTRACTOR} (not flagged print subcontractor),
     *         {@code INVENTORY_ITEM_NOT_FOUND}, {@code VALIDATION_FAILED}
     */
    SubcontractOrder create(CreateSubcontractOrderCommand command);

    Optional<SubcontractOrder> findByProductionOrder(UUID productionOrderId);

    Optional<SubcontractOrder> findById(UUID purchaseOrderId);

    /**
     * Add quantity on a resend (the subcontractor reprints a shortfall, 03 §16 "PO supplement"). A DRAFT is
     * resized in place. An approved order (APPROVED up to RECEIVED) gets an AMENDMENT revision that waits for
     * approval by someone other than who asked (BR-02, four eyes); until it is approved the order keeps its
     * approved quantity, and production cannot send out more than that. A submitted order is approved against the
     * revision frozen at submission, so a pending one is rejected back to DRAFT first; a closed or cancelled one
     * takes nothing more. One amendment waits at a time.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PURCHASE_ORDER_NOT_FOUND},
     *         {@code INVALID_PURCHASE_ORDER_TRANSITION}, {@code CONFLICT} (an amendment already waits)
     */
    SubcontractOrder supplement(UUID purchaseOrderId, int additionalQuantity, String reason, UUID actorId);

    /**
     * Production is done with the order (its production order completed, was cancelled or was replaced): a pending
     * amendment is withdrawn; an order nothing was received on is CANCELLED, one received in part is closed short
     * (its open remainder written off); a fully received, closed or cancelled order is left as it is. Idempotent.
     */
    SubcontractOrder closeForProduction(UUID purchaseOrderId, String reason, UUID actorId);

    /**
     * Records the order's settlement once (BR-PRD-17, G10), priced at its unit price and the subcontractor's loss
     * tolerance: FINAL, or AWAITING_APPROVAL when blanks were lost above tolerance. A second call for the same order
     * returns the settlement already recorded.
     */
    SubcontractSettlement settle(SettleSubcontractCommand command);

    Optional<SubcontractSettlement> settlement(UUID purchaseOrderId);

    /**
     * BR-PRD-17, decision QA1: the warehouse manager approves a loss above tolerance, with the deduction per lost
     * blank agreed with the subcontractor and the reason (required) — never the supplier's current price or the blanks'
     * purchase price. The settlement becomes FINAL; the deduction is recorded, not paid or transferred. Approving the
     * same deduction again is a replay.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}, {@code VALIDATION_FAILED},
     *         {@code CONFLICT} (nothing to approve, or approved with another deduction)
     */
    SubcontractSettlement approveLoss(UUID purchaseOrderId, java.math.BigDecimal deductionPerBlank, String note,
                                      UUID approverId);

    /** A supplier's subcontracting terms: whether it prints for us, and the blanks loss it may have (BR-17). */
    Optional<SubcontractorTerms> subcontractorTerms(UUID supplierId);
}
