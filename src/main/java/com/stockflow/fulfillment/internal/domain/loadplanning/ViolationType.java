package com.stockflow.fulfillment.internal.domain.loadplanning;

/** A hard loading rule a placement breaks (kltn-docs 09 BR-10, checked on every manual move by BR-11). */
public enum ViolationType {
    /** It stands out of the cargo box. */
    OUT_OF_BOUNDS,
    /** It shares volume with another carton. */
    OVERLAP,
    /** Less than 98 % of its base rests on the floor or on the tops of cartons directly beneath. */
    UNSUPPORTED,
    /** The vehicle's cartons together outweigh its payload. */
    OVERWEIGHT,
    /** It is turned although it may not be, or its placed sizes are not its own in any orientation. */
    ROTATION_NOT_ALLOWED,
    /** It reaches into a reserved ceiling corner. */
    GUSSET,
    /** Its placed width x height face is larger than the vehicle's door, so it could not be loaded that way. */
    DOOR,
    /** Its physical set (kit group) is spread over more than one vehicle. */
    KIT_SPLIT
}
