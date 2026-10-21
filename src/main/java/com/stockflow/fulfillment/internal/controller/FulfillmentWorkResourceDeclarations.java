package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declarations of the fulfillment permissions that replaced the role lists of the pick, pack and dispatch
 * endpoints (V20261021000600). One class per resource, as {@link PermissionResource} is not repeatable; each is seeded
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

    @PermissionResource(code = FulfillmentResources.DESIGN_REVERIFICATION, group = "Fulfillment",
            label = "Design-integrity holds: see and re-verify", route = "/fulfillment/packages",
            apiPath = "/api/v1/fulfillment/tasks", actions = {Action.READ, Action.UPDATE})
    static final class DesignReverification { private DesignReverification() { } }

    @PermissionResource(code = FulfillmentResources.CARRIERS, group = "Fulfillment", label = "Carrier catalogue",
            route = "/fulfillment/carriers", apiPath = "/api/v1/fulfillment/carriers", actions = {Action.UPDATE})
    static final class Carriers { private Carriers() { } }

    @PermissionResource(code = FulfillmentResources.DISPATCH, group = "Fulfillment",
            label = "Dispatch: loads, loading and handover", route = "/fulfillment/loads",
            apiPath = "/api/v1/fulfillment/loads", actions = {Action.CREATE, Action.UPDATE})
    static final class Dispatch { private Dispatch() { } }

    @PermissionResource(code = FulfillmentResources.LOAD_CANCELLATIONS, group = "Fulfillment",
            label = "Load cancellation", route = "/fulfillment/loads", apiPath = "/api/v1/fulfillment/loads",
            actions = {Action.UPDATE})
    static final class LoadCancellations { private LoadCancellations() { } }

    @PermissionResource(code = FulfillmentResources.DELIVERY_OUTCOMES, group = "Fulfillment",
            label = "Delivery outcomes", route = "/fulfillment/shipments", apiPath = "/api/v1/fulfillment/shipments",
            actions = {Action.UPDATE, Action.APPROVE})
    static final class DeliveryOutcomes { private DeliveryOutcomes() { } }
}
