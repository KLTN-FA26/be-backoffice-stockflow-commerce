package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a goods receipt (docs 03 §5.1, {@code ck_goods_receipts_status}). Mapped
 * {@code EnumType.STRING}.
 *
 * <p>{@code PENDING_APPROVAL}: historical, no row holds it (SCRUM-477, BR-RCP-001). A purchase receipt counted beyond a
 * PO line's over-receipt tolerance waited in it for the warehouse manager. Purchasing is gone (4A-8) and
 * {@code V20261113000200} took it out of {@code ck_goods_receipts_status}.</p>
 *
 * <p>{@code CONFIRMED}: historical, no row holds it. It was the moment the count is final, and the receipt left it for
 * {@code IN_PUTAWAY} in the same transaction, so no row ever stayed in it; {@code V20261214000100} took it out of the
 * CHECK. Both constants stay so an old value read back is named, never guessed. There is no QC status: receiving QC
 * was removed (kltn-docs 03 BR-07) and {@code V20261108000200} moved the receipts that were in it on.</p>
 */
public enum GoodsReceiptStatus {
    DRAFT, PENDING_APPROVAL, CONFIRMED, IN_PUTAWAY, CLOSED, CANCELLED
}
