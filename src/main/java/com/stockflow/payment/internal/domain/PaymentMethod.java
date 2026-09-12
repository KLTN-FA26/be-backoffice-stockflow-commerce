package com.stockflow.payment.internal.domain;

/**
 * How a payment was tendered. Mapped {@code EnumType.STRING}.
 *
 * <p>Deliberately a separate enum from {@code com.stockflow.contracts.PaymentMethod} even though
 * the values currently match. This one may gain values freely; adding a value to the contract
 * enum is a breaking change for every listener. {@link #toContract()} is the single translation
 * point, and its exhaustive switch fails the build if a new internal value has no contract
 * counterpart — so the two can never drift apart unnoticed.</p>
 */
public enum PaymentMethod {

    CARD,
    BANK_TRANSFER,
    COD,
    E_WALLET,
    DEPOSIT;

    public com.stockflow.contracts.PaymentMethod toContract() {
        return switch (this) {
            case CARD -> com.stockflow.contracts.PaymentMethod.CARD;
            case BANK_TRANSFER -> com.stockflow.contracts.PaymentMethod.BANK_TRANSFER;
            case COD -> com.stockflow.contracts.PaymentMethod.COD;
            case E_WALLET -> com.stockflow.contracts.PaymentMethod.E_WALLET;
            case DEPOSIT -> com.stockflow.contracts.PaymentMethod.DEPOSIT;
        };
    }
}
