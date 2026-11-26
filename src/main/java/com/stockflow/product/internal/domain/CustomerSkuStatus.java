package com.stockflow.product.internal.domain;

/**
 * Lifecycle of a customer's own SKU ({@code ck_customer_skus_status}).
 *
 * <pre>
 *   (design approved) ──▶ ACTIVE ◀──activate── BLOCKED
 *                           │  └────block────▶   │
 *                           └──────obsolete──────┴──▶ OBSOLETE
 * </pre>
 *
 * <p>A SKU is issued {@code ACTIVE} when its design is approved (kltn-docs 12 §4.5): the approval stands in for the
 * activation a catalogue variant goes through, so there is no draft. Only an {@code ACTIVE} SKU is quoted and shown to
 * its customer; {@code OBSOLETE} is terminal - the SKU stays for the quotes, orders, stock and chats that name it.</p>
 */
public enum CustomerSkuStatus {
    ACTIVE,
    BLOCKED,
    OBSOLETE;

    public boolean canTransitionTo(CustomerSkuStatus target) {
        return switch (this) {
            case ACTIVE -> target == BLOCKED || target == OBSOLETE;
            case BLOCKED -> target == ACTIVE || target == OBSOLETE;
            case OBSOLETE -> false;
        };
    }
}
