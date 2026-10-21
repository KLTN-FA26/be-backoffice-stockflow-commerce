package com.stockflow.payment.internal.domain;

/**
 * How money reached the business (kltn-docs 15 §4.5, 09 §4.3 step 4): a customer's bank transfer, or a carrier
 * remitting the balance it collected on delivery. Mapped {@code EnumType.STRING}.
 */
public enum TransferChannel {
    BANK_TRANSFER,
    CARRIER_REMITTANCE
}
