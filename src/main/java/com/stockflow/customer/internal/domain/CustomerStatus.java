package com.stockflow.customer.internal.domain;

/**
 * Lifecycle of a customer profile. kltn-docs 18 §5 maps onto it as Pending Activation =
 * PENDING_ACTIVATION, Active = ACTIVE, Suspended = BLOCKED, Closed = INACTIVE (V20261014000100).
 *
 * <p>{@code EnumType.STRING} in the entity — an ordinal would silently reinterpret every row the
 * day someone reorders these constants.</p>
 */
public enum CustomerStatus {

    /**
     * Sales created the customer and invited its contact, who has not activated the portal account
     * (kltn-docs 18 §5 "Pending Activation"). Activation is the only way to ACTIVE.
     */
    PENDING_ACTIVATION,

    /** Can log in, see prices, quote and order (BR-01a). */
    ACTIVE,

    /** Not transacting: closed, or a legacy profile that never activated (kltn-docs 18 "Closed"). */
    INACTIVE,

    /** Barred by staff — fraud, chargebacks. Kept, not deleted, for the order history behind it. */
    BLOCKED
}
