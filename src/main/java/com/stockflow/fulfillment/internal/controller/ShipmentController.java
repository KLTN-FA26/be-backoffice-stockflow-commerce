package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.AssignCarrierRequest;
import com.stockflow.fulfillment.internal.controller.dto.DeliveryOutcomeRequest;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentRowResponse;
import com.stockflow.fulfillment.internal.service.Shipments;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Shipments in the back office (G4-2a; kltn-docs 09 §5): the list dispatch builds loads from, a shipment's parcels and
 * timeline, the carrier chosen for it, and the outcome recorded when the carrier reports back (§4.3).
 *
 * <p>Outcomes have a resource of their own, {@code fulfillment-delivery-outcomes}: UPDATE records one, and on its own
 * only RETURNED (09 §5 "Warehouse": the warehouse manager may record a shipment back at the warehouse until the
 * returns receipt, SCRUM-295, records it itself); DELIVERED and DELIVERY_FAILED also need APPROVE, the Order
 * Coordinator's (09 BR-02), as they move the order and bill it.</p>
 */
@RestController
@RequestMapping("/api/v1/fulfillment/shipments")
@Tag(name = "Fulfillment - dispatch")
class ShipmentController {

    private final Shipments shipments;
    private final DispatchWebMapper mapper;

    ShipmentController(Shipments shipments, DispatchWebMapper mapper) {
        this.shipments = shipments;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Shipments of the caller's warehouses, newest first; filters warehouseId, status "
            + "(READY_TO_DISPATCH, HANDED_OVER, DELIVERED, DELIVERY_FAILED, RETURNED, CANCELLED), orderId, carrierId, "
            + "search (code)")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<PageResponse<ShipmentRowResponse>> list(@RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID carrierId, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(shipments.list(warehouseId, status, orderId, carrierId, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{shipmentId}")
    @Operation(summary = "A shipment with its parcels and timeline; 404 SHIPMENT_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<ShipmentResponse> get(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(mapper.toResponse(shipments.get(shipmentId)));
    }

    @PutMapping("/{shipmentId}/carrier")
    @Operation(summary = "Choose the carrier of a shipment waiting for dispatch (null clears it); 409 "
            + "SHIPMENT_ON_ANOTHER_LOAD, SHIPMENT_STATE_CONFLICT, CARRIER_INACTIVE")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "shipment", resourceId = "#shipmentId")
    public ApiResponse<ShipmentResponse> assignCarrier(@PathVariable UUID shipmentId,
            @RequestBody AssignCarrierRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(shipments.assignCarrier(shipmentId, request.carrierId(), actor)));
    }

    @PostMapping("/{shipmentId}/delivery-outcome")
    @Operation(summary = "Record what the carrier reported: DELIVERED, DELIVERY_FAILED (with failureReason; OTHER needs "
            + "a note) or RETURNED; the order follows and bills a delivered shipment. fulfillment-delivery-outcomes:UPDATE; "
            + "DELIVERED / DELIVERY_FAILED also :APPROVE. 409 SHIPMENT_STATE_CONFLICT")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOMES, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "shipment", resourceId = "#shipmentId")
    public ApiResponse<ShipmentResponse> recordOutcome(@PathVariable UUID shipmentId,
            @Valid @RequestBody DeliveryOutcomeRequest request, @AuthenticatedUser CurrentUser actor) {
        // 09 BR-02: delivered / not delivered are the Order Coordinator's; the grant says who that is.
        if (!"RETURNED".equals(request.outcome())) {
            actor.require(FulfillmentResources.DELIVERY_OUTCOMES, Action.APPROVE);
        }
        return ApiResponse.ok(mapper.toResponse(shipments.recordOutcome(shipmentId, new Shipments.Outcome(
                request.outcome(), request.failureReason(), request.note()), actor)));
    }
}
