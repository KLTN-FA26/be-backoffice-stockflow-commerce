package com.stockflow.warehouse.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The putaway trips of the warehouse module (kltn-docs 05 §4.5), for the inventory module that orchestrates them: it
 * chooses the bins and moves the stock, this module keeps the cart, the holds and the route.
 *
 * <p>Every method runs in the caller's transaction. A trip row is always locked before a task row or a bin: every
 * method that changes a trip locks it first, and {@link #lockTripCarrying} lets a putaway of a single task take the
 * same order.</p>
 */
public interface PutawayTrips {

    /**
     * Opens a trip for the clerk, {@code LOADING}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TRIP_ALREADY_OPEN} when the clerk has a
     *         trip that is not finished (05 BR-15)
     */
    PutawayTripView open(UUID warehouseId, UUID operatorId);

    /** @return empty when there is no such trip */
    Optional<PutawayTripView> find(UUID tripId);

    /** The clerk's {@code LOADING} or {@code IN_PROGRESS} trip. */
    Optional<PutawayTripView> openTripOf(UUID operatorId);

    /** @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TRIP_NOT_FOUND} */
    PutawayTripView lockForUpdate(UUID tripId);

    /** Locks the open trip whose cart carries the task, if there is one. */
    Optional<PutawayTripView> lockTripCarrying(UUID putawayTaskId);

    /**
     * Takes the task's carton onto the cart of a {@code LOADING} trip.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TRIP_INVALID_STATE};
     *         {@code PUTAWAY_TASK_NOT_FOUND}; {@code PUTAWAY_TASK_DONE} for a task that is not {@code PENDING};
     *         {@code PUTAWAY_TASK_ON_ANOTHER_TRIP} (05 BR-15)
     */
    PutawayTripView load(UUID tripId, UUID putawayTaskId);

    /** Takes a carton off the cart of a {@code LOADING} trip. */
    PutawayTripView unload(UUID tripId, UUID putawayTaskId);

    /**
     * The clerk sets off: the trip is {@code IN_PROGRESS}, every carton holds the space at the location chosen for
     * it, and the stops are ordered from the start area (05 §4.5 steps 13-14).
     *
     * @param assignedLocationByTask the bin or OVERFLOW area of each carton on the cart; absent for a carton nothing
     *                               takes
     * @param startLocationId        the source area the clerk sets off from
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TRIP_EMPTY}, {@code PUTAWAY_TRIP_INVALID_STATE}
     */
    PutawayTripView start(UUID tripId, Map<UUID, UUID> assignedLocationByTask, UUID startLocationId);

    /** A carton on its way gets another bin; the old hold ends, the route is ordered again from where the clerk is. */
    PutawayTripView reassign(UUID tripId, UUID putawayTaskId, UUID locationId);

    /**
     * Tells the trip carrying the task, if any, that its carton was put away. The trip is {@code COMPLETED} with its
     * last carton. A carton put away while its trip was still {@code LOADING} just leaves the cart.
     */
    void taskPutAway(UUID putawayTaskId);

    /**
     * Ends an open trip: the cartons still on the cart go back, their tasks {@code PENDING}, the holds are released.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TRIP_INVALID_STATE} for a finished trip
     */
    PutawayTripView close(UUID tripId, UUID closedBy);

    /**
     * The space the trips on their way hold in a warehouse.
     *
     * @param exceptTripId a trip whose own holds are not wanted; null for all
     */
    List<PutawayHold> holds(UUID warehouseId, UUID exceptTripId);
}
