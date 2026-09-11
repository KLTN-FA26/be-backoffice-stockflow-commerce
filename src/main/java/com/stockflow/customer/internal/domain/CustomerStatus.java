package com.stockflow.customer.internal.domain;

/**
 * Lifecycle of a customer profile.
 *
 * <p>{@code EnumType.STRING} in the entity — an ordinal would silently reinterpret every row the
 * day someone reorders these constants.</p>
 */
public enum CustomerStatus {

    /** Can log in, place orders. */
    ACTIVE,

    /** Registered but not (yet) transacting — self-deactivated or never activated. */
    INACTIVE,

    /** Barred by staff — fraud, chargebacks. Kept, not deleted, for the order history behind it. */
    BLOCKED
}
