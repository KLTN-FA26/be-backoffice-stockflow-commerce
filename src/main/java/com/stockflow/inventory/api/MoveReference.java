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
    ORDER
}
