package com.stockflow.fulfillment.internal.domain.loadplanning;

/** Why a carton is left out of the plan (kltn-docs 09 edge case "kiện không xếp được ... kèm lý do"). */
public enum UnplacedReason {
    /** It fits no vehicle type the plan may use, even empty, in any orientation it may take. */
    TOO_LARGE,
    /** It fits a type but outweighs the payload of every type it fits. */
    TOO_HEAVY,
    /** Every vehicle of the types that could take it is already in the plan (BR-12). */
    NO_VEHICLE_AVAILABLE,
    /**
     * Vehicles remain but it found no room: a fill carton (it never opens a vehicle), or a set part whose set could
     * not be completed in any vehicle.
     */
    NO_SPACE,
    /**
     * Vehicles remained, but the time budget ran out before the search could load it ({@code meta.budgetExhausted}
     * is set). A longer budget, or recalculating, may place it.
     */
    SEARCH_CUT_OFF
}
