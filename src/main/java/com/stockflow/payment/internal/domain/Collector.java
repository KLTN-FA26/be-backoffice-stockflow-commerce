package com.stockflow.payment.internal.domain;

/**
 * Who pays a receivable (G2-3): the customer, by transfer - or the carrier, which collected the balance of a deposit
 * order on delivery and remits it (kltn-docs 09 §2, §4.3 step 4). A carrier-collected receivable is the carrier's
 * debt, not the customer's: it is not in their credit position and their transfers never pay it. Mapped
 * {@code EnumType.STRING}.
 */
public enum Collector {
    CUSTOMER,
    CARRIER
}
