package com.stockflow.fulfillment.internal.service;

/**
 * Permission resources of the fulfillment module that a service checks as well as a controller (seeded by
 * V20261021000600). The controller layer reads them through {@code FulfillmentResources}; they live here because a
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

    /**
     * READ: tasks held for a design-integrity check and their confirmed design files; UPDATE: re-verify and release
     * the hold.
     */
    public static final String DESIGN_REVERIFICATION = "fulfillment-design-reverification";

    /** UPDATE: add and edit carriers in the carrier catalogue. */
    public static final String CARRIERS = "fulfillment-carriers";

    /**
     * CREATE: open a load; UPDATE: choose a shipment's carrier, put shipments on a load, scan and unscan parcels, and
     * sign the manifest (handover).
     */
    public static final String DISPATCH = "fulfillment-dispatch";

    /** UPDATE: call a load off before it left. */
    public static final String LOAD_CANCELLATIONS = "fulfillment-load-cancellations";

    /**
     * UPDATE: record a delivery outcome - on its own, RETURNED only; APPROVE: also DELIVERED and DELIVERY_FAILED, which
     * move the order and bill it (kltn-docs 09 BR-02).
     */
    public static final String DELIVERY_OUTCOMES = "fulfillment-delivery-outcomes";

    private FulfillmentPermissions() {
    }
}
