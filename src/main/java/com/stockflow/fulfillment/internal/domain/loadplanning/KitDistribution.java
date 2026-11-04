package com.stockflow.fulfillment.internal.domain.loadplanning;

/** How declared sets are spread over the vehicles (the original's {@code distribute}). */
public enum KitDistribution {
    /** Try {@link #FIRST} and {@link #EVEN} and keep whichever loads better. */
    AUTO,
    /** Pile the sets into the first vehicle; what does not fit rolls over to the next. */
    FIRST,
    /** Spread the sets over the vehicles, in proportion to their volume when the types differ. */
    EVEN
}
