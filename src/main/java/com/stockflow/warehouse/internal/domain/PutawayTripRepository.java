package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link PutawayTrip}. */
public interface PutawayTripRepository extends AggregateRepository<PutawayTrip, UUID> {

    /** Reads under a row lock and refreshes, so what is returned is the committed trip and not a stale copy. */
    Optional<PutawayTrip> findByIdForUpdate(UUID id);

    /** The clerk's LOADING or IN_PROGRESS trip; at most one ({@code uk_putaway_trip_open_operator}). */
    Optional<PutawayTrip> findOpenByOperator(UUID operatorId);

    /** The id of the open trip whose cart carries this task; at most one ({@code uk_putaway_trip_item_on_cart}). */
    Optional<UUID> findOpenTripCarrying(UUID putawayTaskId);

    /**
     * The space held in a warehouse by the open trips, except one.
     *
     * @param exceptTripId the trip whose own holds are not wanted; null for every trip
     */
    List<Hold> findHolds(UUID warehouseId, UUID exceptTripId);

    /**
     * Gives back the space of every IN_PROGRESS trip that saw no scan since {@code idleSince} (05 BR-16); the trips
     * and their cartons stay as they are.
     *
     * @return the holds released
     */
    int releaseHoldsOfTripsIdleSince(Instant idleSince, Instant now);

    /** Space held for the carton {@code stockPackageId} of {@code putawayTaskId} at {@code locationId}. */
    record Hold(UUID tripId, UUID putawayTaskId, UUID stockPackageId, UUID locationId) {
    }
}
