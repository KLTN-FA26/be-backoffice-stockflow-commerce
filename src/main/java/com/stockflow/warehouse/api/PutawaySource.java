package com.stockflow.warehouse.api;

/**
 * Why a putaway task exists (kltn-docs 05 BR-01): there are exactly two sources of goods to put into a bin. Stored as
 * {@code warehouse.putaway_task.source} and checked by {@code ck_putaway_task_source}.
 */
public enum PutawaySource {
    /** A carton of a confirmed goods-receipt line, INBOUND at the receiving area; the task names the line. */
    RECEIPT,
    /**
     * A carton a warehouse manager released from quarantine (kltn-docs 05 §4.4, 20 §4.8): QUARANTINE at the quarantine
     * area until the task puts it into a bin, AVAILABLE from then on (05 BR-14). No receipt line.
     */
    QUARANTINE_RELEASE
}
