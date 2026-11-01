package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a goods receipt (docs 03 §5.1, {@code ck_goods_receipts_status}). Mapped
 * {@code EnumType.STRING}.
 *
 * <p>{@code PENDING_APPROVAL} (SCRUM-477, BR-RCP-001): a count beyond a PO line's over-receipt tolerance, sent to the
 * warehouse manager. Nothing is counted in while it waits; it leaves for {@code IN_QC} or {@code IN_PUTAWAY} (approved,
 * or the excess rejected and the rest posted), or for {@code CANCELLED}. A receipt in it is not a posted receipt: no
 * stock, no purchase order progress.</p>
 *
 * <p>{@code CONFIRMED} is the moment the count is final; the receipt leaves it in the same
 * transaction, to {@code IN_QC} when a line needs QC and to {@code IN_PUTAWAY} otherwise, so no row
 * stays in it today. It is kept because the table and the docs name it.</p>
 */
public enum GoodsReceiptStatus {
    DRAFT, PENDING_APPROVAL, CONFIRMED, IN_QC, IN_PUTAWAY, CLOSED, CANCELLED
}
