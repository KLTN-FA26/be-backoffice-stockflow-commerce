package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a goods receipt (docs 03 §5.1, {@code ck_goods_receipts_status}). Mapped
 * {@code EnumType.STRING}.
 *
 * <p>{@code PENDING_APPROVAL}: legacy (SCRUM-477, BR-RCP-001). A purchase receipt counted beyond a PO line's
 * over-receipt tolerance waited in it for the warehouse manager, counted in nowhere. Purchasing is gone (4A-8): no
 * receipt enters it any more, and one left in it is only cancelled. Kept because the table names it.</p>
 *
 * <p>{@code CONFIRMED} is the moment the count is final; the receipt leaves it for {@code IN_PUTAWAY} in the same
 * transaction, so no row stays in it. It is kept because the table names it. There is no QC status: receiving QC was
 * removed (kltn-docs 03 BR-07) and {@code V20261108000200} moved the receipts that were in it on.</p>
 */
public enum GoodsReceiptStatus {
    DRAFT, PENDING_APPROVAL, CONFIRMED, IN_PUTAWAY, CLOSED, CANCELLED
}
