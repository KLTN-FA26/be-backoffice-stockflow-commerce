package com.stockflow.procurement.internal.domain;

/**
 * What a goods receipt is against (kltn-docs 03 §1, {@code ck_goods_receipts_type}). Mapped {@code EnumType.STRING}.
 *
 * <p>{@code PURCHASE}: a purchase order, or none at all (a blind receipt, SCRUM-478) - the receipts there were before
 * 4A-5, kept until purchasing is removed (4A-8). {@code PRODUCTION}: one production order (4A-5): finished goods in
 * whole cartons, in the production order's lot, with no tolerance and no approval.</p>
 */
public enum GoodsReceiptType {
    PURCHASE, PRODUCTION
}
