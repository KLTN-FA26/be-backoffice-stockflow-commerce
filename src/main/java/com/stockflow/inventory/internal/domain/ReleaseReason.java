package com.stockflow.inventory.internal.domain;

/**
 * Why a hold was given up.
 *
 * <p>Deliberately a separate enum from {@code com.stockflow.contracts.ReleaseReason} even though
 * the values currently match. This one may gain values freely; adding a value to the contract
 * enum is a breaking change for every listener. {@link #toContract()} is the single translation
 * point, and its exhaustive switch fails the build if a new internal value has no contract
 * counterpart — so the two can never drift apart unnoticed.</p>
 */
public enum ReleaseReason {

    ORDER_CANCELLED,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    MANUAL_OVERRIDE;

    public com.stockflow.contracts.ReleaseReason toContract() {
        return switch (this) {
            case ORDER_CANCELLED -> com.stockflow.contracts.ReleaseReason.ORDER_CANCELLED;
            case PAYMENT_FAILED -> com.stockflow.contracts.ReleaseReason.PAYMENT_FAILED;
            case RESERVATION_EXPIRED -> com.stockflow.contracts.ReleaseReason.RESERVATION_EXPIRED;
            case MANUAL_OVERRIDE -> com.stockflow.contracts.ReleaseReason.MANUAL_OVERRIDE;
        };
    }
}
