package com.stockflow.inventory.api;

/**
 * The document behind a move, as the stock ledger records it.
 *
 * <p>Production issues blanks against {@link #ORDER} (the sales order id) until the ledger gains a
 * production-order reference type.</p>
 */
public enum MoveReference {
    MOVE_TASK,
    PUTAWAY_TASK,
    TRANSFER_ORDER_LINE,
    ORDER,
    /** A goods-receipt line: moving received goods from the receiving area to the QC area. */
    GOODS_RECEIPT_LINE,
    /** A QC decision on received goods: quarantined or rejected parts leave the QC area. */
    QC_INSPECTION
}
