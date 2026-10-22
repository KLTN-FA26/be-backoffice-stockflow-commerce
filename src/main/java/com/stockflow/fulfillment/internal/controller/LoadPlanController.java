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
import com.stockflow.fulfillment.internal.controller.dto.CreateLoadPlanRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadPlacementsRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadPlanInputsRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadPlanReasonRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadPlanVersionRequest;
import com.stockflow.fulfillment.internal.controller.dto.VehicleRecalculationRequest;
import com.stockflow.fulfillment.internal.service.LoadPlans;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Load planning of full-truckload shipping (SCRUM-287 PR D / SCRUM-391; kltn-docs 09 §4.2, §5, BR-09..BR-15).
 * {@code fulfillment-load-plans}: READ to look (the order coordinator too), CREATE to start a plan, UPDATE for every
 * step after - seeded for warehouse staff (09 §2 Dispatch Staff) and the warehouse manager. Every call is limited to the
 * caller's warehouses. Each change sends the plan's {@code expectedVersion}; another one is 409 OPTIMISTIC_LOCK.
 *
 * <p>The layout is the {@code Layout} contract of the 3D viewer (FE #71): mm, x across, y up, z toward the door,
 * origin the inner-left floor corner by the front wall.</p>
 */
@RestController
@RequestMapping("/api/v1/fulfillment")
@Tag(name = "Fulfillment - load planning")
class LoadPlanController {

    private final LoadPlans loadPlans;

    LoadPlanController(LoadPlans loadPlans) {
        this.loadPlans = loadPlans;
    }

    @GetMapping("/load-plan-candidates")
    @Operation(summary = "Full-truckload shipments of the warehouse waiting for dispatch, whole, with the packages no "
            + "other live plan holds (sizes in mm, weight, rotatable, kit); loadPlanId counts that plan's own as free")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<List<LoadPlans.CandidateShipment>> candidates(@RequestParam UUID warehouseId,
                                                                     @RequestParam(required = false) UUID loadPlanId) {
        return ApiResponse.ok(loadPlans.candidates(warehouseId, loadPlanId));
    }

    @PostMapping("/load-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a load plan (DRAFT), optionally with its shipments, vehicle types and mode; "
            + "idempotent by requestId. 409 LOAD_PLAN_PACKAGE_TAKEN, SHIPMENT_STATE_CONFLICT, SHIPMENT_NOT_FOR_LOAD, "
            + "VEHICLE_TYPE_INACTIVE; 422 LOAD_PLAN_PACKAGE_UNMEASURED")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.CREATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "load-plan", resourceId = "#result?.data()?.loadPlanId()")
    public ApiResponse<LoadPlans.PlanDetail> create(@Valid @RequestBody CreateLoadPlanRequest request,
                                                    @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.create(new LoadPlans.CreatePlan(request.requestId(), request.warehouseId(),
                request.shipmentIds(), request.vehicleTypeIds(), request.selectionMode(), request.note()), actor));
    }

    @GetMapping("/load-plans")
    @Operation(summary = "Load plans of the caller's warehouses, newest first; status DRAFT, PLANNED, CONFIRMED, "
            + "DISPATCHED, CANCELLED; search in the code")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<PageResponse<LoadPlans.PlanRow>> list(@RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(loadPlans.list(warehouseId, status, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size));
    }

    @GetMapping("/load-plans/{loadPlanId}")
    @Operation(summary = "A load plan: shipments, vehicle types, vehicles (fill, dispatch load), packages with their "
            + "vehicle or unplaced reason, acknowledgement; 404 LOAD_PLAN_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<LoadPlans.PlanDetail> get(@PathVariable UUID loadPlanId) {
        return ApiResponse.ok(loadPlans.get(loadPlanId));
    }

    @PutMapping("/load-plans/{loadPlanId}/inputs")
    @Operation(summary = "Replace the shipments (whole), vehicle types and mode; PLANNED goes back to DRAFT (09 §5). "
            + "409 OPTIMISTIC_LOCK, INVALID_LOAD_PLAN_TRANSITION, LOAD_PLAN_PACKAGE_TAKEN")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> setInputs(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanInputsRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.setInputs(loadPlanId, new LoadPlans.Inputs(request.expectedVersion(),
                request.shipmentIds(), request.vehicleTypeIds(), request.selectionMode()), actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/calculation")
    @Operation(summary = "Run the planner over the whole plan (synchronous, capped by the solver budget) and store "
            + "the vehicles and placements: PLANNED. 409 LOAD_PLAN_EMPTY, VEHICLE_TYPE_INACTIVE")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> calculate(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanVersionRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.calculate(loadPlanId, request.expectedVersion(), actor));
    }

    @GetMapping("/load-plans/{loadPlanId}/layouts")
    @Operation(summary = "The Layout of every vehicle of the plan, in seq order (one colour map for the whole plan)")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<List<LoadPlans.Layout>> layouts(@PathVariable UUID loadPlanId) {
        return ApiResponse.ok(loadPlans.layouts(loadPlanId));
    }

    @GetMapping("/load-plans/{loadPlanId}/vehicles/{seq}/layout")
    @Operation(summary = "One vehicle's Layout for the 3D viewer (FE #71 contract); 404 LOAD_PLAN_VEHICLE_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<LoadPlans.Layout> layout(@PathVariable UUID loadPlanId, @PathVariable int seq) {
        return ApiResponse.ok(loadPlans.layout(loadPlanId, seq));
    }

    @PutMapping("/load-plans/{loadPlanId}/placements")
    @Operation(summary = "Manual edit of the whole plan, all or nothing (BR-11): every vehicle with its boxes, and the "
            + "tray. 422 PLACEMENT_RULE_VIOLATION with fieldErrors [{field: ref, code: rule}] (OUT_OF_BOUNDS, OVERLAP, "
            + "UNSUPPORTED, OVERWEIGHT, ROTATION_NOT_ALLOWED, GUSSET, DOOR, KIT_SPLIT); 400 VALIDATION_FAILED when the "
            + "edit does not list every package exactly once")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> editPlacements(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlacementsRequest request, @AuthenticatedUser CurrentUser actor) {
        List<LoadPlans.VehicleBoxes> vehicles = request.vehicles().stream().map(v -> new LoadPlans.VehicleBoxes(
                v.seq(), v.boxes().stream().map(b -> new LoadPlans.BoxPosition(b.ref(), b.xMm(), b.yMm(), b.zMm(),
                        b.wMm(), b.hMm(), b.dMm(), b.orientation())).toList())).toList();
        return ApiResponse.ok(loadPlans.editPlacements(loadPlanId, new LoadPlans.PlacementEdit(
                request.expectedVersion(), vehicles, request.tray()), actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/vehicles/{seq}/recalculation")
    @Operation(summary = "Re-solve one vehicle with its packages and, optionally, packages from the tray; what does not "
            + "fit goes to the tray. 409 INVALID_LOAD_PLAN_TRANSITION; 404 LOAD_PLAN_VEHICLE_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> recalculateVehicle(@PathVariable UUID loadPlanId, @PathVariable int seq,
            @Valid @RequestBody VehicleRecalculationRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.recalculateVehicle(loadPlanId, seq, request.expectedVersion(),
                request.addPackageIds(), actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/unplaced-acknowledgement")
    @Operation(summary = "Accept that the unplaced packages stay at the dispatch area (BR-13). 409 "
            + "ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED (LP-4); 422 PLACEMENT_RULE_VIOLATION KIT_SPLIT")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> acknowledgeUnplaced(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanVersionRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.acknowledgeUnplaced(loadPlanId, request.expectedVersion(), request.note(),
                actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/confirmation")
    @Operation(summary = "Confirm: lock the packages and layout; unplaced ones go back to the dispatch area; one "
            + "PLANNED dispatch load per vehicle. 409 LOAD_PLAN_UNPLACED_NOT_ACKNOWLEDGED (BR-13), "
            + "ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED (LP-4), LOAD_PLAN_EMPTY, SHIPMENT_STATE_CONFLICT")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> confirm(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanVersionRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.confirm(loadPlanId, request.expectedVersion(), actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/reopening")
    @Operation(summary = "Back to PLANNED while no vehicle loads (BR-14); its dispatch loads, booked ones included, are "
            + "cancelled with the reason (LP-11). 409 LOAD_PLAN_LOADING_STARTED")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> reopen(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanReasonRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.reopen(loadPlanId, request.expectedVersion(), request.reason(), actor));
    }

    @PostMapping("/load-plans/{loadPlanId}/cancellation")
    @Operation(summary = "Cancel while no vehicle loads (BR-14): packages back to the dispatch area, dispatch loads "
            + "cancelled. 409 LOAD_PLAN_LOADING_STARTED")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "load-plan", resourceId = "#loadPlanId")
    public ApiResponse<LoadPlans.PlanDetail> cancel(@PathVariable UUID loadPlanId,
            @Valid @RequestBody LoadPlanReasonRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(loadPlans.cancel(loadPlanId, request.expectedVersion(), request.reason(), actor));
    }

    @GetMapping("/load-plans/{loadPlanId}/reconciliation")
    @Operation(summary = "Piece check: packages in = placed + unplaced, for the plan, per shipment and per order; "
            + "vsEstimate stays null until the estimate is built (PR EST)")
    @RequiresPermission(resource = FulfillmentResources.LOAD_PLANS, action = Action.READ)
    public ApiResponse<LoadPlans.Reconciliation> reconciliation(@PathVariable UUID loadPlanId) {
        return ApiResponse.ok(loadPlans.reconciliation(loadPlanId));
    }
}
