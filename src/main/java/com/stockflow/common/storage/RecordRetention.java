package com.stockflow.common.storage;

/**
 * Stored files that are business records and must not change or disappear for a time once the
 * record is final. Each is locked with S3 Object Lock for the period
 * {@link ObjectLockProperties} configures; nothing else is ever locked.
 */
public enum RecordRetention {

    /** Every file of a design bundle the customer confirmed: what was approved is what is printed. */
    CONFIRMED_DESIGN,

    /** A supplier's invoice as received: an accounting record. */
    SUPPLIER_INVOICE
}
