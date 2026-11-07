package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a goods receipt (docs 03 §5.1, {@code ck_goods_receipts_status}). Mapped
 * {@code EnumType.STRING}.
 *
 * <p>{@code PENDING_APPROVAL} (SCRUM-477, BR-RCP-001): a count beyond a PO line's over-receipt tolerance, sent to the
 * warehouse manager. Nothing is counted in while it waits; it leaves for {@code IN_PUTAWAY} (approved, or the excess
 * rejected and the rest posted), or for {@code CANCELLED}. A receipt in it is not a posted receipt: no stock, no
 * purchase order progress.</p>
 *
 * <p>{@code CONFIRMED} is the moment the count is final; the receipt leaves it for {@code IN_PUTAWAY} in the same
 * transaction, so no row stays in it. It is kept because the table names it. There is no QC status: receiving QC was
 * removed (kltn-docs 03 BR-07) and {@code V20261108000200} moved the receipts that were in it on.</p>
 */
public enum GoodsReceiptStatus {
    DRAFT, PENDING_APPROVAL, CONFIRMED, IN_PUTAWAY, CLOSED, CANCELLED
}
