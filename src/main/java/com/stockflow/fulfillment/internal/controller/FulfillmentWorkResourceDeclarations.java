package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declarations of the fulfillment permissions that replaced the role lists of the pick, pack and dispatch
 * endpoints (V20261022000200). One class per resource, as {@link PermissionResource} is not repeatable; each is seeded
 * to exactly the roles the old role list let through, SYSTEM_ADMIN included.
 */
final class FulfillmentWorkResourceDeclarations {

    private FulfillmentWorkResourceDeclarations() { }

    @PermissionResource(code = FulfillmentResources.TASK_ASSIGNMENT, group = "Fulfillment",
            label = "Fulfillment tasks: open and assign", route = "/fulfillment/picks",
            apiPath = "/api/v1/fulfillment/tasks", actions = {Action.CREATE, Action.UPDATE})
    static final class TaskAssignment { private TaskAssignment() { } }

    @PermissionResource(code = FulfillmentResources.TASK_OVERSIGHT, group = "Fulfillment",
            label = "Fulfillment tasks: every task of my warehouses", route = "/fulfillment/picks",
            apiPath = "/api/v1/fulfillment/tasks", actions = {Action.READ})
    static final class TaskOversight { private TaskOversight() { } }

    @PermissionResource(code = FulfillmentResources.PICKING, group = "Fulfillment", label = "Picking and put-back",
            route = "/fulfillment/picks", apiPath = "/api/v1/fulfillment/tasks",
            actions = {Action.READ, Action.UPDATE})
    static final class Picking { private Picking() { } }

    @PermissionResource(code = FulfillmentResources.PACKING, group = "Fulfillment", label = "Packing station",
            route = "/fulfillment/packages", apiPath = "/api/v1/fulfillment/tasks",
            actions = {Action.READ, Action.UPDATE, Action.APPROVE})
    static final class Packing { private Packing() { } }

    @PermissionResource(code = FulfillmentResources.CARRIERS, group = "Fulfillment", label = "Carrier catalogue",
            route = "/fulfillment/carriers", apiPath = "/api/v1/fulfillment/carriers", actions = {Action.UPDATE})
    static final class Carriers { private Carriers() { } }

    @PermissionResource(code = FulfillmentResources.DISPATCH, group = "Fulfillment",
            label = "Dispatch: booking, loading and handover", route = "/fulfillment/loads",
            apiPath = "/api/v1/fulfillment/loads", actions = {Action.UPDATE})
    static final class Dispatch { private Dispatch() { } }

    @PermissionResource(code = FulfillmentResources.CONTROLLED_CANCELLATIONS, group = "Fulfillment",
            label = "Controlled cancel after loading started", route = "/fulfillment/loads",
            apiPath = "/api/v1/fulfillment", actions = {Action.UPDATE})
    static final class ControlledCancellations { private ControlledCancellations() { } }

    @PermissionResource(code = FulfillmentResources.WAYBILL_CORRECTIONS, group = "Fulfillment",
            label = "Waybill correction after the vehicle left", route = "/fulfillment/loads",
            apiPath = "/api/v1/fulfillment/loads", actions = {Action.UPDATE})
    static final class WaybillCorrections { private WaybillCorrections() { } }

    @PermissionResource(code = FulfillmentResources.WAYBILL_IMPORTS, group = "Fulfillment",
            label = "Waybill numbers imported from a file", route = "/fulfillment/waybill-imports",
            apiPath = "/api/v1/fulfillment/waybill-imports", actions = {Action.READ, Action.CREATE})
    static final class WaybillImports { private WaybillImports() { } }

    @PermissionResource(code = FulfillmentResources.DELIVERY_OUTCOMES, group = "Fulfillment",
            label = "Delivery outcomes", route = "/fulfillment/shipments", apiPath = "/api/v1/fulfillment/shipments",
            actions = {Action.UPDATE, Action.APPROVE})
    static final class DeliveryOutcomes { private DeliveryOutcomes() { } }
}
