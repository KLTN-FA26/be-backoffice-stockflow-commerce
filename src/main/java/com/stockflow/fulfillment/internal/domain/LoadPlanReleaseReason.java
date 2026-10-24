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
    ORDER_CANCELLED
}
