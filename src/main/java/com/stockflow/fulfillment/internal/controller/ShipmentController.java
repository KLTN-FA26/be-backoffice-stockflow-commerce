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
import com.stockflow.common.security.Role;
import com.stockflow.common.security.Roles;
import com.stockflow.fulfillment.internal.controller.dto.AssignCarrierRequest;
import com.stockflow.fulfillment.internal.controller.dto.DeliveryOutcomeRequest;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentResponse;
import com.stockflow.fulfillment.internal.controller.dto.ShipmentRowResponse;
import com.stockflow.fulfillment.internal.service.Shipments;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * <p>Delivered / not delivered are the Order Coordinator's to record (09 BR-02), and warehouse staff hold UPDATE on
 * this resource for loading - so the outcome is limited by role here, not only by permission. A shipment back at the
 * warehouse (RETURNED, 09 §5 "Warehouse") may also be recorded by the warehouse manager until the returns receipt
 * (SCRUM-295) records it itself.</p>
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
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "')")
    @Auditable(action = AuditAction.UPDATE, resourceType = "shipment", resourceId = "#shipmentId")
    public ApiResponse<ShipmentResponse> assignCarrier(@PathVariable UUID shipmentId,
            @RequestBody AssignCarrierRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(shipments.assignCarrier(shipmentId, request.carrierId(), actor)));
    }

    @PostMapping("/{shipmentId}/delivery-outcome")
    @Operation(summary = "Record what the carrier reported: DELIVERED, DELIVERY_FAILED (with failureReason; OTHER needs "
            + "a note) or RETURNED; the order follows and bills a delivered shipment. Order Coordinator; RETURNED also "
            + "the warehouse manager. 409 SHIPMENT_STATE_CONFLICT")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.ORDER_COORDINATOR + "','" + Roles.WAREHOUSE_MANAGER + "')")
    @Auditable(action = AuditAction.TRANSITION, resourceType = "shipment", resourceId = "#shipmentId")
    public ApiResponse<ShipmentResponse> recordOutcome(@PathVariable UUID shipmentId,
            @Valid @RequestBody DeliveryOutcomeRequest request, @AuthenticatedUser CurrentUser actor) {
        // 09 BR-02: delivered / not delivered are recorded by the Order Coordinator only.
        if (!"RETURNED".equals(request.outcome()) && !actor.hasRole(Role.ORDER_COORDINATOR)) {
            throw new AccessDeniedException("Only the Order Coordinator records a delivery outcome (09 BR-02)");
        }
        return ApiResponse.ok(mapper.toResponse(shipments.recordOutcome(shipmentId, new Shipments.Outcome(
                request.outcome(), request.failureReason(), request.note()), actor)));
    }
}
