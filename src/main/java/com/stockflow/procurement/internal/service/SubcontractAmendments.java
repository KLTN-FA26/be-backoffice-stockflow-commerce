package com.stockflow.procurement.internal.service;

import com.stockflow.procurement.api.SubcontractOrder;

import java.util.UUID;

/**
 * The approver's side of an amendment to an approved SUBCONTRACT order (a resend after a QC shortfall, 03 §16;
 * BR-02: what was ordered changes through a revision approved again). Asked for by production through
 * {@link com.stockflow.procurement.api.SubcontractPurchasing#supplement}; decided here, by someone other than who
 * asked.
 */
public interface SubcontractAmendments {

    /**
     * The amendment's quantity becomes the order's; a fully received order is partially received again, so the
     * extra units can be counted in.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PURCHASE_ORDER_NOT_FOUND}, {@code CONFLICT} (none
     *         waiting), {@code SELF_APPROVAL_NOT_ALLOWED}, {@code PO_APPROVAL_LIMIT_EXCEEDED} (the approver's limit,
     *         when they have one, is below the order's total with the amendment)
     */
    SubcontractOrder approveAmendment(UUID purchaseOrderId, UUID approverId);

    /** The amendment is dropped with a reason; the order keeps its approved quantity. */
    SubcontractOrder rejectAmendment(UUID purchaseOrderId, UUID approverId, String reason);
}
