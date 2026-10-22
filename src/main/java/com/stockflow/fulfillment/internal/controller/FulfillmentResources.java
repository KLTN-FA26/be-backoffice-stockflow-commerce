package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.service.FulfillmentPermissions;

/** Permission resource codes of the fulfillment module (the service-checked ones live in FulfillmentPermissions). */
final class FulfillmentResources {
    static final String PICK_LISTS = FulfillmentPermissions.PICK_LISTS;
    static final String PACKAGES = "fulfillment-packages";
    static final String SHIPMENTS = "fulfillment-shipments";

    static final String TASK_ASSIGNMENT = FulfillmentPermissions.TASK_ASSIGNMENT;
    static final String TASK_OVERSIGHT = FulfillmentPermissions.TASK_OVERSIGHT;
    static final String PICKING = FulfillmentPermissions.PICKING;
    static final String PACKING = FulfillmentPermissions.PACKING;
    static final String DESIGN_REVERIFICATION = FulfillmentPermissions.DESIGN_REVERIFICATION;
    static final String CARRIERS = FulfillmentPermissions.CARRIERS;
    static final String VEHICLE_TYPES = FulfillmentPermissions.VEHICLE_TYPES;
    static final String DISPATCH = FulfillmentPermissions.DISPATCH;
    static final String LOAD_CANCELLATIONS = FulfillmentPermissions.LOAD_CANCELLATIONS;
    static final String DELIVERY_OUTCOMES = FulfillmentPermissions.DELIVERY_OUTCOMES;

    private FulfillmentResources() { }
}
