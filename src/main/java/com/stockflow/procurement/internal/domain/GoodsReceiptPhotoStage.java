package com.stockflow.procurement.internal.domain;

/**
 * When a photo of received goods was taken ({@code ck_goods_receipt_photos_stage}).
 *
 * <ul>
 *   <li>{@code RECEIPT}: by the warehouse, of what arrived - while the receipt is counted (DRAFT) or waits for the
 *       manager (PENDING_APPROVAL); of the delivery as a whole or of one line (04b F-RCV step 05);</li>
 *   <li>{@code QC}: by QC, of a line it inspects, while the receipt is IN_QC (step 07).</li>
 * </ul>
 */
public enum GoodsReceiptPhotoStage {
    RECEIPT, QC;

    /** Whether a photo of this stage is taken while a receipt is {@code status}. */
    public boolean openAt(GoodsReceiptStatus status) {
        return switch (this) {
            case RECEIPT -> status == GoodsReceiptStatus.DRAFT || status == GoodsReceiptStatus.PENDING_APPROVAL;
            case QC -> status == GoodsReceiptStatus.IN_QC;
        };
    }
}
