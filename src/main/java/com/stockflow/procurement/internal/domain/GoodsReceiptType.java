package com.stockflow.procurement.internal.domain;

/**
 * What a goods receipt is against (kltn-docs 03 §1, {@code ck_goods_receipts_type}). Mapped {@code EnumType.STRING}.
 *
 * <p>{@code PURCHASE}: legacy - a purchase order, or none at all (a blind receipt, SCRUM-478), the receipts there were
 * before 4A-5. Purchasing is gone (4A-8): no new one is started, and those there are read, or cancelled while they
 * have counted nothing in. {@code PRODUCTION}: one production order (4A-5): finished goods in whole cartons, in the
 * production order's lot, with no tolerance and no approval. {@code RETURN}: goods that came back (4D-2) - one
 * shipment the carrier returned or one return request - scanned by LPN straight into quarantine
 * ({@link ReturnReceipt}).</p>
 */
public enum GoodsReceiptType {
    PURCHASE, PRODUCTION, RETURN
}
