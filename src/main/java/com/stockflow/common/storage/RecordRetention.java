package com.stockflow.common.storage;

/**
 * Stored files that are business records and must not change or disappear for a time once the
 * record is final. Each is locked with S3 Object Lock for the period
 * {@link ObjectLockProperties} configures; nothing else is ever locked.
 */
public enum RecordRetention {

    /** Every file of a design bundle the customer confirmed: what was approved is what is printed. */
    CONFIRMED_DESIGN,

    /** A sales contract as sent to the customer, and as signed (4B-4, kltn-docs 14 BR-14): what binds both parties. */
    SALES_CONTRACT,

    /**
     * An issued e-invoice's XML and PDF (WF-05 E2): an accounting record, kept at least ten years (Luật Kế toán 2015
     * Điều 41) and never changed once issued (kltn-docs 21 BR-03).
     */
    EINVOICE
}
