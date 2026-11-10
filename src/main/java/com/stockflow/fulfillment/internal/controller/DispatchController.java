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
import com.stockflow.fulfillment.internal.controller.dto.BookingRequest;
import com.stockflow.fulfillment.internal.controller.dto.CancelLoadRequest;
import com.stockflow.fulfillment.internal.controller.dto.ControlledCancelRequest;
import com.stockflow.fulfillment.internal.controller.dto.ControlledCancelResponse;
import com.stockflow.fulfillment.internal.controller.dto.HandoverRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.LoadScanRequest;
import com.stockflow.fulfillment.internal.controller.dto.ManifestResponse;
import com.stockflow.fulfillment.internal.controller.dto.UnloadOverrideRequest;
import com.stockflow.fulfillment.internal.controller.dto.UnloadRequest;
import com.stockflow.fulfillment.internal.service.Dispatching;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Loading and handover of a load plan's vehicles (SCRUM-288/292/293, SCRUM-392; kltn-docs 09 §4.2): every order ships
 * as a full truckload (09 BR-08), so a load is born from a confirmed plan, never opened by hand.
 * {@code fulfillment-shipments:READ} to look (managers too); {@code fulfillment-dispatch:UPDATE} to book, load and
 * sign - seeded for the warehouse (09 §2 Dispatch Staff). Calling a plan's vehicle off after loading
 * started, dropping one from its plan and confirming a parcel off without a scan is
 * {@code fulfillment-controlled-cancellations:UPDATE} (SCRUM-474). Every call is limited to the caller's warehouses.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/loads")
@Tag(name = "Fulfillment - dispatch")
class DispatchController {

    private final Dispatching dispatching;
    private final com.stockflow.fulfillment.internal.service.Bookings bookings;
    private final DispatchWebMapper mapper;

    DispatchController(Dispatching dispatching, com.stockflow.fulfillment.internal.service.Bookings bookings,
                       DispatchWebMapper mapper) {
        this.dispatching = dispatching;
        this.bookings = bookings;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Loads of the caller's warehouses, newest first; status PLANNED, VEHICLE_BOOKED, LOADING, "
            + "DISPATCHED, CANCELLED (400 UNSUPPORTED_PARAMETER otherwise); search in load code, plate or the load "
            + "plan's code")
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
    @Operation(summary = "The manifest to print (09 §4.2 step 5): shipments, recipients, parcels")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<ManifestResponse> manifest(@PathVariable UUID loadId) {
        return ApiResponse.ok(mapper.toResponse(dispatching.manifest(loadId)));
    }

    @PostMapping("/{loadId}/scans")
    @Operation(summary = "Scan a parcel onto the vehicle by its label code; the same scanId again answers as before. "
            + "404 PACKAGE_NOT_FOUND; 409 PACKAGE_NOT_IN_LOAD, PACKAGE_ALREADY_LOADED, PACKAGE_NOT_READY, "
            + "LABEL_OUTDATED, PACKAGE_UNLOAD_PENDING, LOAD_SCAN_ID_CONFLICT, INVALID_LOAD_TRANSITION")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> scan(@PathVariable UUID loadId, @Valid @RequestBody LoadScanRequest request,
                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.scan(loadId, request.scanId(), request.packageCode(),
                actor)));
    }

    @DeleteMapping("/{loadId}/scans/{packageId}")
    @Operation(summary = "Take a scanned parcel off the vehicle again before the handover")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> unscan(@PathVariable UUID loadId, @PathVariable UUID packageId,
                                            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.unscan(loadId, packageId, actor)));
    }

    @PostMapping("/{loadId}/handover")
    @Operation(summary = "Both sides sign the manifest of a load plan's vehicle (SCRUM-392): DISPATCHED once every "
            + "parcel placed on it is scanned; a shipment is HANDED_OVER at its last vehicle, the order "
            + "PARTIALLY_FULFILLED / SHIPPED, the plan DISPATCHED with the last vehicle. 400 HANDOVER_NOT_SIGNED; 409 "
            + "LOAD_EMPTY, LOAD_NOT_FULLY_LOADED, LOAD_UNLOAD_PENDING, OPTIMISTIC_LOCK, ORDER_BALANCE_NOT_PAID, "
            + "ORDER_INVALID_TRANSITION (on hold); again: answered as it is")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> handOver(@PathVariable UUID loadId, @Valid @RequestBody HandoverRequest request,
                                              @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.handOver(loadId, new Dispatching.Handover(
                request.version(), request.carrierRepresentative(), request.vehiclePlate(), request.driverPhone(),
                request.signatureRef(), request.signed(), request.note()), actor)));
    }

    // ---- a load plan's vehicle (SCRUM-392; 09 §4.2 step 5) --------------------------------------------------------

    @PostMapping("/{loadId}/booking")
    @Operation(summary = "A confirmed load plan's vehicle booked with a carrier (outside the system): PLANNED -> "
            + "VEHICLE_BOOKED; again before loading replaces the booking. 404 CARRIER_NOT_FOUND; 409 "
            + "INVALID_LOAD_TRANSITION (loading started), INVALID_LOAD_PLAN_TRANSITION (plan not CONFIRMED), "
            + "CARRIER_INACTIVE, WAYBILL_DUPLICATE (the waybill number, if given). An API_BOOKING carrier (3-4) is booked "
            + "through its API: give pickupAt and the stops' order, not the plate, driver or number - 422 "
            + "CARRIER_PICKUP_NOT_CONFIGURED, CARRIER_SERVICE_NOT_CONFIGURED, CARRIER_STOP_OUT_OF_AREA, "
            + "CARRIER_BOOKING_FAILED (the carrier's reason); 409 CARRIER_BOOKING_IN_PROGRESS; 502 when it does not "
            + "answer")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> book(@PathVariable UUID loadId, @Valid @RequestBody BookingRequest request,
                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(bookings.book(loadId, new com.stockflow.fulfillment.internal.service
                .Bookings.Request(request.carrierId(), request.vehiclePlate(), request.driverName(),
                request.driverPhone(), request.waybillNo(), request.pickupAt(), request.stopShipmentIds()), actor)));
    }

    @PostMapping("/{loadId}/booking-cancellation")
    @Operation(summary = "The booking fell through before loading: back to PLANNED with the reason, to book again. "
            + "An order at an API_BOOKING carrier is cancelled there first: 409 CARRIER_CANCEL_REFUSED when the carrier "
            + "will not (the driver picked up). 409 INVALID_LOAD_TRANSITION once loading started")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> cancelBooking(@PathVariable UUID loadId,
            @Valid @RequestBody CancelLoadRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(bookings.cancelBooking(loadId, request.reason(), actor)));
    }

    @PostMapping("/{loadId}/loading-start")
    @Operation(summary = "The booked vehicle arrived: LOADING - the plan can no longer be reopened or cancelled "
            + "the plain way (BR-14; only the controlled cancellation). 409 INVALID_LOAD_TRANSITION (not booked), INVALID_LOAD_PLAN_TRANSITION")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> startLoading(@PathVariable UUID loadId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.startLoading(loadId, actor)));
    }

    @PostMapping("/{loadId}/unloads")
    @Operation(summary = "A parcel taken off the vehicle (scanned off): one of a cancelled order, or one of a vehicle "
            + "called off under control (also on the CANCELLED load); with nothing left to carry the vehicle is called "
            + "off. 404 PACKAGE_NOT_FOUND; 409 PACKAGE_NOT_IN_LOAD")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> unload(@PathVariable UUID loadId, @Valid @RequestBody UnloadRequest request,
                                            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.unload(loadId, request.packageCode(), actor)));
    }

    @PostMapping("/{loadId}/controlled-cancellation")
    @Operation(summary = "Call off ONE load-plan vehicle that is being loaded (a broken or wrong truck) under control "
            + "(SCRUM-474, 09 BR-14): its load is CANCELLED at once with the reason code (XE_HONG, SAI_XE, "
            + "KIEN_KHONG_VUA, KHAC) and note, its scanned parcels stay locked until each is scanned off "
            + "(POST .../unloads) or confirmed off (POST .../unload-overrides), and a PLANNED load with the same layout "
            + "and manifest replaces it, to book with another truck; the plan and its other vehicles carry on. 409 "
            + "INVALID_LOAD_TRANSITION (not LOADING), INVALID_LOAD_PLAN_TRANSITION; 400 "
            + "VALIDATION_FAILED. Again: answered as it is")
    @RequiresPermission(resource = FulfillmentResources.CONTROLLED_CANCELLATIONS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<ControlledCancelResponse> cancelControlled(@PathVariable UUID loadId,
            @Valid @RequestBody ControlledCancelRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.cancelControlled(loadId, request.reasonCode(),
                request.note(), actor)));
    }

    @PostMapping("/{loadId}/plan-removal")
    @Operation(summary = "Drop a load-plan vehicle that has not loaded (its load still PLANNED, no truck booked) from its "
            + "confirmed plan once every other vehicle of the plan left (SCRUM-474, UNLOAD-DROP): its load is CANCELLED "
            + "with the reason code (XE_HONG, SAI_XE, KIEN_KHONG_VUA, KHAC) and note (required), the vehicle leaves the "
            + "plan, the parcels placed on it wait for a new plan and the plan is DISPATCHED. 409 "
            + "INVALID_LOAD_TRANSITION (not PLANNED), INVALID_LOAD_PLAN_TRANSITION, "
            + "LOAD_PLAN_OTHER_VEHICLES_NOT_LEFT, LOAD_PLAN_VEHICLE_SHARES_ORDER; 400 VALIDATION_FAILED. Again: "
            + "answered as it is")
    @RequiresPermission(resource = FulfillmentResources.CONTROLLED_CANCELLATIONS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> removeFromPlan(@PathVariable UUID loadId,
            @Valid @RequestBody ControlledCancelRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.removeFromPlan(loadId, request.reasonCode(),
                request.note(), actor)));
    }

    @PostMapping("/{loadId}/unload-overrides")
    @Operation(summary = "A parcel still to unload confirmed off the vehicle WITHOUT a scan because its label is "
            + "damaged (SCRUM-474, UNLOAD-DESIGN point 5): who, when and the note stay on record. 404 "
            + "PACKAGE_NOT_FOUND; 409 PACKAGE_NOT_IN_LOAD (not to be unloaded from this load)")
    @RequiresPermission(resource = FulfillmentResources.CONTROLLED_CANCELLATIONS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId")
    public ApiResponse<LoadResponse> overrideUnload(@PathVariable UUID loadId,
            @Valid @RequestBody UnloadOverrideRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.overrideUnload(loadId, request.packageId(), request.note(),
                actor)));
    }
}
