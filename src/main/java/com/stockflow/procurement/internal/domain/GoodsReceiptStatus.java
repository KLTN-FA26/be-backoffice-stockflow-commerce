package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a goods receipt (docs 03 §5.1, {@code ck_goods_receipts_status}). Mapped
 * {@code EnumType.STRING}.
 *
 * <p>{@code CONFIRMED} is the moment the count is final; the receipt leaves it in the same
 * transaction, to {@code IN_QC} when a line needs QC and to {@code IN_PUTAWAY} otherwise, so no row
 * stays in it today. It is kept because the table and the docs name it.</p>
 */
public enum GoodsReceiptStatus {
    DRAFT, CONFIRMED, IN_QC, IN_PUTAWAY, CLOSED, CANCELLED
}
