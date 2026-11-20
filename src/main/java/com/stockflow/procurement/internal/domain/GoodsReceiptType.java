package com.stockflow.procurement.internal.domain;

/**
 * What a goods receipt is against (kltn-docs 03 §1, {@code ck_goods_receipts_type}). Mapped {@code EnumType.STRING}.
 *
 * <p>{@code PURCHASE}: historical, no row holds it. It was a purchase order or none at all (a blind receipt,
 * SCRUM-478); purchasing went in 4A-8 and {@code V20261214000100} deleted the last receipts and took the value out of
 * the CHECK. The constant stays so an old value read back is named, never guessed. {@code PRODUCTION}: one production order (4A-5): finished goods in whole cartons, in the
 * production order's lot, with no tolerance and no approval. {@code RETURN}: goods that came back (4D-2) - one
 * shipment the carrier returned or one return request - scanned by LPN straight into quarantine
 * ({@link ReturnReceipt}).</p>
 */
public enum GoodsReceiptType {
    PURCHASE, PRODUCTION, RETURN
}
