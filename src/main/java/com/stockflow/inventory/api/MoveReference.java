package com.stockflow.inventory.api;

/**
 * The document behind a move, as the stock ledger records it.
 */
public enum MoveReference {
    MOVE_TASK,
    PUTAWAY_TASK,
    TRANSFER_ORDER_LINE,
    ORDER,
    /** A goods-receipt line: received goods moved on from the receiving area. */
    GOODS_RECEIPT_LINE,
    /** A return line (SCRUM-260): returned stock items QC passed, out of quarantine and back on sale at a bin. */
    RETURN_REQUEST_LINE
}
