package com.stockflow.fulfillment.internal.service;

/**
 * Permission resources of the fulfillment module that a service checks as well as a controller (seeded by
 * V20261022000200). The controller layer reads them through {@code FulfillmentResources}; they live here because a
 * service may not depend on the controller package.
 *
 * <p>Every check is a permission, never a role name: an administrator who ticks one of these for another role in the
 * matrix screen (ADR-0008) widens the rule without a code change.</p>
 */
public final class FulfillmentPermissions {

    /** READ: the task list (data-scoped further by the codes below), a task and its pick lines. */
    public static final String PICK_LISTS = "fulfillment-pick-lists";

    /** CREATE opens a task for a released order; UPDATE (re)assigns it and lists who it can go to. */
    public static final String TASK_ASSIGNMENT = "fulfillment-task-assignment";

    /** READ: every task of the caller's warehouses, not only the ones assigned to them. */
    public static final String TASK_OVERSIGHT = "fulfillment-task-oversight";

    /** READ: the put-back queue; UPDATE: start, scan-confirm, complete picking and put goods back. */
    public static final String PICKING = "fulfillment-picking";

    /**
     * READ: a package label; UPDATE: work the packing station (start, packages, scans, seal, reopen, complete) - and,
     * once picking is done, see the task at the packing table; APPROVE: work or take over another packer's session.
     */
    public static final String PACKING = "fulfillment-packing";

    /** UPDATE: add and edit carriers in the carrier catalogue. */
    public static final String CARRIERS = "fulfillment-carriers";

    /** READ: the vehicle-type catalogue of load planning; UPDATE: declare and edit vehicle types (V20261022000700). */
    public static final String VEHICLE_TYPES = "fulfillment-vehicle-types";

    /**
     * CREATE: estimate how a released order loads before packing (advisory, LP2-1); READ: an estimate and the current
     * estimate of an order (V20261022001200).
     */
    public static final String LOAD_ESTIMATES = "fulfillment-load-estimates";

    /**
     * UPDATE: book a load plan's vehicle (and cancel the booking), start loading, scan and unscan parcels, scan off a
     * parcel to unload, and sign the manifest (handover). Loads are born from confirmed plans: nothing opens one.
     */
    public static final String DISPATCH = "fulfillment-dispatch";

    /**
     * UPDATE: call a load plan, or one of its vehicles, off AFTER loading started - the controlled cancel, with a reason
     * code and a note - and confirm a parcel off the vehicle without a scan when its label is damaged (SCRUM-474
     * UNLOAD-DESIGN points 5-6; V20261102020100). Warehouse manager and system admin only.
     */
    public static final String CONTROLLED_CANCELLATIONS = "fulfillment-controlled-cancellations";

    /**
     * UPDATE: correct a load's waybill number after the vehicle left the warehouse, with a reason (3-2; kltn-docs 09
     * BR-24 "sau khi rời kho chỉ Quản lý kho"; V20261108001400). Before that, recording it is
     * {@link #DISPATCH}'s. Warehouse manager and system admin only.
     */
    public static final String WAYBILL_CORRECTIONS = "fulfillment-waybill-corrections";

    /**
     * UPDATE: record a delivery outcome - on its own, RETURNED only; APPROVE: also DELIVERED and DELIVERY_FAILED, which
     * move the order and bill it (kltn-docs 09 BR-02).
     */
    public static final String DELIVERY_OUTCOMES = "fulfillment-delivery-outcomes";

    private FulfillmentPermissions() {
    }
}
