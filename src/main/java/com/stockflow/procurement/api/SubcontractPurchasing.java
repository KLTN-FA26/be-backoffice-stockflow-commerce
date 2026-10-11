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
     * Add quantity on a resend (the subcontractor reprints a shortfall). Only while the PO is still
     * DRAFT. A submitted order is approved against the revision frozen at submission, so a pending one
     * is rejected back to DRAFT first; an approved one is changed by an amendment that is approved
     * again (PO revisions, SCRUM-117). Neither is edited in place.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PURCHASE_ORDER_NOT_FOUND},
     *         {@code INVALID_PURCHASE_ORDER_TRANSITION}
     */
    SubcontractOrder supplement(UUID purchaseOrderId, int additionalQuantity, String reason, UUID actorId);

    /** A supplier's subcontracting terms: whether it prints for us, and the blanks loss it may have (BR-17). */
    Optional<SubcontractorTerms> subcontractorTerms(UUID supplierId);
}
