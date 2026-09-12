package com.stockflow.contracts;

/**
 * How the customer paid (BRD 3.15).
 *
 * <p>Deliberately a copy of payment-service's internal enum rather than a shared class: the
 * internal one may gain values freely, while adding a value here is a contract change every
 * consumer has to cope with. Coupling them would make refactoring payment a breaking change. See
 * {@code payment.internal.domain.PaymentMethod#toContract()} for the single translation point.</p>
 */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER,
    E_WALLET,
    COD,
    DEPOSIT
}
