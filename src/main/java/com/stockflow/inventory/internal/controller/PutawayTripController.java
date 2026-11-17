package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.PutawayTripRequests;
import com.stockflow.inventory.internal.controller.dto.PutawayTripResponse;
import com.stockflow.inventory.internal.service.PutawayTripService;
import com.stockflow.inventory.internal.service.PutawayTripService.TripView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Putaway trips (kltn-docs 05 §4.5; Jira SCRUM-531): a clerk's cart of cartons, put away along one route. Served
 * next to the putaway tasks, under its own resource {@code warehouse-putaway-trips}: closing somebody else's trip is
 * the warehouse manager's ({@link Action#APPROVE}), which the tasks' resource has no action for.
 *
 * <p>Every handler is public: {@code @RequiresPermission} is applied by a proxy, and a handler that is not public
 * may be left without it.</p>
 */
@RestController
@Tag(name = "Putaway trips", description = "Several cartons on one cart: bins assigned for the cart, space held, "
        + "stops ordered on the warehouse map")
@RequestMapping("/api/v1/putaway-trips")
@PermissionResource(
        code = InventoryResources.PUTAWAY_TRIPS,
        group = "Warehouse",
        label = "Putaway trips",
        route = "/warehouse/putaway-trips",
        apiPath = "/api/v1/putaway-trips",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE, Action.APPROVE})
class PutawayTripController {

    private final PutawayTripService trips;

    PutawayTripController(PutawayTripService trips) {
        this.trips = trips;
    }

    @PostMapping
    @Operation(summary = "Open a putaway trip in a warehouse",
            description = "The trip is LOADING: scan cartons onto the cart next. A clerk has one open trip at a time "
                    + "(409 PUTAWAY_TRIP_ALREADY_OPEN, with the open trip's id).")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> open(@Valid @RequestBody PutawayTripRequests.Open request,
                                                 @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.open(request.warehouseId(), actor.userId())));
    }

    @GetMapping("/current")
    @Operation(summary = "The caller's open putaway trip", description = "data is null when the caller has none.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> current(@AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(trips.current(actor.userId()).map(PutawayTripController::toResponse).orElse(null));
    }

    @GetMapping("/{tripId}")
    @Operation(summary = "One putaway trip: its cart and, once it set off, its stops in order")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.READ, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> get(@PathVariable UUID tripId) {
        return ApiResponse.ok(toResponse(trips.get(tripId)));
    }

    @PostMapping("/{tripId}/cartons")
    @Operation(summary = "Scan a carton onto the cart",
            description = "Takes {lpn}. The carton must have a pending putaway task and stand in the trip's warehouse. "
                    + "Refused: on another trip's cart 409 PUTAWAY_TASK_ON_ANOTHER_TRIP; no task waiting 404; the "
                    + "cart already left 409 PUTAWAY_TRIP_INVALID_STATE. Scanning a carton twice changes nothing.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> load(@PathVariable UUID tripId,
                                                 @Valid @RequestBody PutawayTripRequests.Carton request,
                                                 @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.load(tripId, request.lpn(), actor.userId())));
    }

    @DeleteMapping("/{tripId}/cartons/{lpn}")
    @Operation(summary = "Take a carton off the cart before setting off")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> unload(@PathVariable UUID tripId, @PathVariable String lpn,
                                                   @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.unload(tripId, lpn, actor.userId())));
    }

    @PostMapping("/{tripId}/departure")
    @Operation(summary = "Set off: assign a bin to every carton on the cart, hold the space, order the stops",
            description = "The trip becomes IN_PROGRESS. Bins follow the putaway ranking (same lot, empty bin, same "
                    + "SKU, other SKU); a carton no bin takes is given the OVERFLOW area, the last stop. An empty "
                    + "cart is 422 PUTAWAY_TRIP_EMPTY.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> start(@PathVariable UUID tripId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.start(tripId, actor.userId())));
    }

    @PostMapping("/{tripId}/placements")
    @Operation(summary = "At a stop: put a carton of the cart into the scanned bin",
            description = "Takes {locationCode, lpn}. Any bin that takes the carton is accepted, not only the one "
                    + "assigned; the refusals are those of a single putaway (409 LOCATION_CAPACITY_EXCEEDED, 422 "
                    + "CARTON_DOES_NOT_FIT_BIN, ...). The trip is COMPLETED with its last carton.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> place(@PathVariable UUID tripId,
                                                  @Valid @RequestBody PutawayTripRequests.Placement request,
                                                  @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.place(tripId, request.locationCode(), request.lpn(),
                actor.userId())));
    }

    @PostMapping("/{tripId}/cartons/{lpn}/reassignment")
    @Operation(summary = "Give a carton on its way another bin",
            description = "For a carton whose assigned bin was refused at the scan: the next bin the ranking offers "
                    + "now, or the OVERFLOW area. The old hold is released and the stops are ordered again.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> reassign(@PathVariable UUID tripId, @PathVariable String lpn,
                                                     @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.reassign(tripId, lpn, actor.userId())));
    }

    @PostMapping("/{tripId}/closure")
    @Operation(summary = "End your own trip with cartons still on the cart",
            description = "The cartons go back to their source area with their tasks pending; the holds are released.")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> close(@PathVariable UUID tripId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.close(tripId, actor.userId(), false)));
    }

    @PostMapping("/{tripId}/forced-closure")
    @Operation(summary = "Close a trip another clerk left behind (warehouse manager)")
    @RequiresPermission(resource = InventoryResources.PUTAWAY_TRIPS, action = Action.APPROVE, scope = DataScope.WAREHOUSE)
    public ApiResponse<PutawayTripResponse> closeForAnother(@PathVariable UUID tripId,
                                                            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(trips.close(tripId, actor.userId(), true)));
    }

    private static PutawayTripResponse toResponse(TripView trip) {
        return new PutawayTripResponse(trip.id(), trip.warehouseId(), trip.operatorId(), trip.status(),
                trip.startLocationCode(), trip.startX(), trip.startY(), trip.startedAt(), trip.lastActivityAt(),
                trip.closedAt(), trip.closedBy(),
                trip.cartons().stream().map(c -> new PutawayTripResponse.Carton(c.putawayTaskId(), c.lpn(), c.sku(),
                        c.lotNumber(), c.status(), c.assignedLocationCode(), c.stopSequence(), c.holding(),
                        c.loadedAt(), c.placedAt())).toList(),
                trip.stops().stream().map(s -> new PutawayTripResponse.Stop(s.sequence(), s.locationCode(), s.x(),
                        s.y(), s.lpns())).toList());
    }
}
