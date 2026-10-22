package com.stockflow.fulfillment.internal.domain;

import com.stockflow.fulfillment.internal.domain.loadplanning.UnplacedReason;

/**
 * Why a parcel held by a load plan has no place (kltn-docs 09 §3 {@code LoadPlanUnplaced.reason}): the planner's
 * reasons ({@link UnplacedReason}), and {@link #MANUAL} for a parcel the dispatcher put on the tray by hand.
 */
public enum LoadPlanUnplacedReason {
    TOO_LARGE,
    TOO_HEAVY,
    NO_VEHICLE_AVAILABLE,
    NO_SPACE,
    SEARCH_CUT_OFF,
    MANUAL;

    public static LoadPlanUnplacedReason of(UnplacedReason reason) {
        return valueOf(reason.name());
    }
}
