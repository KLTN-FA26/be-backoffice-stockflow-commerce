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
import com.stockflow.common.security.Roles;
import com.stockflow.fulfillment.internal.controller.dto.CancelLoadRequest;
import com.stockflow.fulfillment.internal.controller.dto.HandoverRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadScanRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadShipmentsRequest;
import com.stockflow.fulfillment.internal.controller.dto.ManifestResponse;
import com.stockflow.fulfillment.internal.controller.dto.OpenLoadRequest;
import com.stockflow.fulfillment.internal.service.Dispatching;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Loading and handover, parcel branch (G4-2a, SCRUM-288/292/293; kltn-docs 09 §4.1). Resource
 * {@code fulfillment-shipments}: READ to look (coordinators too), CREATE to open a load, UPDATE to load and sign.
 * Loading and signing are the warehouse's (09 §2 Dispatch Staff): {@code @PreAuthorize} keeps coordinators, who hold
 * UPDATE for delivery outcomes, off the vehicle. Cancelling a load is the warehouse manager's. Every call is limited to
 * the caller's warehouses.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/loads")
@Tag(name = "Fulfillment - dispatch")
class DispatchController {

    private static final String DISPATCHERS = "hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','"
            + Roles.WAREHOUSE_MANAGER + "')";

    private final Dispatching dispatching;
    private final DispatchWebMapper mapper;

    DispatchController(Dispatching dispatching, DispatchWebMapper mapper) {
        this.dispatching = dispatching;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Loads of the caller's warehouses, newest first; status PLANNED, VEHICLE_BOOKED, LOADING, "
            + "DISPATCHED, CANCELLED; search in load code or plate")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<PageResponse<LoadRowResponse>> list(@RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(dispatching.list(warehouseId, status, carrierId, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(mapper::toResponse));
    }

    @GetMapping("/{loadId}")
    @Operation(summary = "A load with its manifest: shipments, parcels, which are on the vehicle; 404 LOAD_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<LoadResponse> get(@PathVariable UUID loadId) {
        return ApiResponse.ok(mapper.toResponse(dispatching.get(loadId)));
    }

    @GetMapping("/{loadId}/manifest")
    @Operation(summary = "The manifest to print (09 §4.1 step 3): shipments, recipients, parcels, COD per shipment and "
            + "in total")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<ManifestResponse> manifest(@PathVariable UUID loadId) {
        return ApiResponse.ok(mapper.toResponse(dispatching.manifest(loadId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "The carrier's driver arrived: open a parcel load (LOADING) with the shipments for that "
            + "carrier; idempotent by requestId")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.CREATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.CREATE, resourceType = "dispatch-load", resourceId = "#result?.data()?.loadId()")
    public ApiResponse<LoadResponse> open(@Valid @RequestBody OpenLoadRequest request,
                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.open(new Dispatching.OpenLoad(request.requestId(),
                request.warehouseId(), request.carrierId(), request.shipmentIds(), request.vehiclePlate(),
                request.driverName(), request.driverPhone()), actor)));
    }

    @PostMapping("/{loadId}/shipments")
    @Operation(summary = "Put shipments on the manifest: waiting for dispatch, this warehouse, this carrier or none "
            + "yet; 409 SHIPMENT_ON_ANOTHER_LOAD, SHIPMENT_OTHER_CARRIER, SHIPMENT_NOT_FOR_LOAD, SHIPMENT_STATE_CONFLICT")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> addShipments(@PathVariable UUID loadId,
            @Valid @RequestBody LoadShipmentsRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.addShipments(loadId, request.shipmentIds(), actor)));
    }

    @DeleteMapping("/{loadId}/shipments/{shipmentId}")
    @Operation(summary = "Take a shipment and its scanned parcels off the manifest before the handover")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> removeShipment(@PathVariable UUID loadId, @PathVariable UUID shipmentId,
                                                    @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.removeShipment(loadId, shipmentId, actor)));
    }

    @PostMapping("/{loadId}/scans")
    @Operation(summary = "Scan a parcel onto the vehicle by its label code; the same scanId again answers as before. "
            + "404 PACKAGE_NOT_FOUND; 409 PACKAGE_NOT_IN_LOAD, PACKAGE_ALREADY_LOADED, PACKAGE_NOT_READY, "
            + "PACKAGE_OTHER_CARRIER, PACKAGE_REQUIRES_LOAD_PLAN, LOAD_SCAN_ID_CONFLICT, INVALID_LOAD_TRANSITION")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> scan(@PathVariable UUID loadId, @Valid @RequestBody LoadScanRequest request,
                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.scan(loadId, request.scanId(), request.packageCode(),
                actor)));
    }

    @DeleteMapping("/{loadId}/scans/{packageId}")
    @Operation(summary = "Take a scanned parcel off the vehicle again before the handover")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> unscan(@PathVariable UUID loadId, @PathVariable UUID packageId,
                                            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.unscan(loadId, packageId, actor)));
    }

    @PostMapping("/{loadId}/handover")
    @Operation(summary = "Both sides sign the manifest: DISPATCHED, shipments HANDED_OVER with the COD the order "
            + "fixes, orders PARTIALLY_FULFILLED / SHIPPED. 400 HANDOVER_NOT_SIGNED; 409 LOAD_EMPTY, "
            + "LOAD_NOT_FULLY_LOADED, OPTIMISTIC_LOCK, ORDER_BALANCE_NOT_PAID, ORDER_INVALID_TRANSITION (on hold); "
            + "again: answered as it is")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize(DISPATCHERS)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> handOver(@PathVariable UUID loadId, @Valid @RequestBody HandoverRequest request,
                                              @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.handOver(loadId, new Dispatching.Handover(
                request.version(), request.carrierRepresentative(), request.vehiclePlate(), request.driverPhone(),
                request.signatureRef(), request.signed(), request.note()), actor)));
    }

    @PostMapping("/{loadId}/cancellation")
    @Operation(summary = "Call a load off before it left; its shipments and parcels go back to the dispatch area")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.UPDATE)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_MANAGER + "')")
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> cancel(@PathVariable UUID loadId, @Valid @RequestBody CancelLoadRequest request,
                                            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.cancel(loadId, request.reason(), actor)));
    }
}
