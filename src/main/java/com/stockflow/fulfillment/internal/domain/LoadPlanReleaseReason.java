package com.stockflow.fulfillment.internal.domain;

/** Why a load plan stopped holding a parcel (BR-09): the parcel may then join another plan. */
public enum LoadPlanReleaseReason {
    /** Taken out of the plan's inputs (09 §4.2 step 3: "đổi kiện"). */
    INPUTS_CHANGED,
    /** Unplaced and acknowledged, at confirmation: back to the dispatch area for a later trip (09 §4.2 step 3, BR-13). */
    LEFT_BEHIND,
    /** The plan was cancelled: its parcels return to the dispatch area (09 §5). */
    PLAN_CANCELLED,
    /** Its order was cancelled (09 edge "đơn bị huỷ khi kiện đã nằm trong kế hoạch chuyến xe"). */
    ORDER_CANCELLED,
    /**
     * Its vehicle was dropped from a confirmed plan whose other vehicles already left, no truck coming for it (SCRUM-474
     * UNLOAD-DROP): the parcel waits for a new plan, as one not planned yet.
     */
    VEHICLE_DROPPED,
    /**
     * Taken off a vehicle being loaded through its controlled cancel - damaged, or its order's installment before
     * delivery unpaid (09 §4.2 step 9, §5.3, BR-14, decided 2026-11-05): it waits at the dispatch area for another
     * plan, and the vehicle's new load carries a layout recalculated for the parcels it kept.
     */
    REMOVED_FROM_VEHICLE
}
