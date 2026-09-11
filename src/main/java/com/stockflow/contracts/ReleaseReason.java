package com.stockflow.contracts;

/**
 * Why a stock reservation was released.
 *
 * <p>Deliberately a copy of inventory-service's internal enum rather than a shared class: the
 * internal one may gain values freely, while adding a value here is a contract change every
 * consumer has to cope with. Coupling them would make refactoring inventory a breaking change.</p>
 */
public enum ReleaseReason {
    ORDER_CANCELLED,
    PAYMENT_FAILED,
    RESERVATION_EXPIRED,
    MANUAL_OVERRIDE
}
