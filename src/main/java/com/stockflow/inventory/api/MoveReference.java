package com.stockflow.inventory.api;

/**
 * The document behind a move, as the stock ledger records it.
 */
public enum MoveReference {
    MOVE_TASK,
    PUTAWAY_TASK,
    TRANSFER_ORDER_LINE,
    ORDER,
    /** A goods-receipt line: moving received goods from the receiving area to the QC area. */
    GOODS_RECEIPT_LINE,
    /** A QC decision on received goods: quarantined or rejected parts leave the QC area. */
    QC_INSPECTION,
    /** Blanks issued to the PRODUCTION area, printed goods moved on to PACKING (SCRUM-397/400). */
    PRODUCTION_ORDER
}
