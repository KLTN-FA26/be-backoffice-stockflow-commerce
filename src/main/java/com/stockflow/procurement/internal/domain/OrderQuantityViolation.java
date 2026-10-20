package com.stockflow.procurement.internal.domain;

/**
 * Why a quantity cannot be ordered from a supplier item. Both can apply to one quantity, so they
 * are reported together and the caller shows every reason at once.
 */
public enum OrderQuantityViolation {
    /** The quantity is under the supplier's minimum order quantity. */
    BELOW_MOQ,
    /** The quantity is not a whole number of packs. */
    NOT_PACK_MULTIPLE
}
