package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A putaway trip as another module sees it (kltn-docs 05 §4.5): a snapshot, not the aggregate.
 *
 * @param status          {@code LOADING}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code CLOSED}; a string so the
 *                        module's own enum stays internal
 * @param startLocationId the source area the route starts from; null until the trip set off
 * @param startX          where the route starts on the map; null when the trip has not set off or the map cannot say
 * @param originLocationId where the stops still to visit were ordered from: the place of the last carton put away,
 *                         else the start area (issue #410). Null unless the trip is on its way and the map can place it
 * @param originX          that origin's point on the map: the first point of the first stop's {@code path}
 * @param closedBy        who closed it with cartons still on the cart; null for a trip that completed
 * @param cartons         every carton that is or was on the cart, in the order they came onto it
 * @param stops           the places still to visit, in visiting order; empty unless the trip is on its way. Worked
 *                        out from the map when the snapshot is taken, never stored (06 §4.4 step 6)
 */
public record PutawayTripView(UUID id, UUID warehouseId, UUID operatorId, String status, UUID startLocationId,
                              BigDecimal startX, BigDecimal startY, UUID originLocationId, BigDecimal originX,
                              BigDecimal originY, Instant startedAt, Instant lastActivityAt, Instant closedAt,
                              UUID closedBy, List<Carton> cartons, List<Stop> stops) {

    public PutawayTripView {
        cartons = List.copyOf(cartons);
        stops = List.copyOf(stops);
    }

    public boolean open() {
        return "LOADING".equals(status) || "IN_PROGRESS".equals(status);
    }

    /** The carton of {@code putawayTaskId} if it is on the cart now. */
    public java.util.Optional<Carton> onCart(UUID putawayTaskId) {
        return cartons.stream().filter(c -> c.putawayTaskId().equals(putawayTaskId) && "ON_CART".equals(c.status()))
                .findFirst();
    }

    /**
     * @param status             {@code ON_CART}, {@code PLACED} or {@code RETURNED}
     * @param assignedLocationId the bin (or OVERFLOW area) chosen when the trip set off; null before, and for a
     *                           carton nothing takes
     * @param stopSequence       its stop on the route, from 1; cartons for one bin share it
     * @param holding            whether its space at the assigned bin is still held (05 BR-16)
     */
    public record Carton(UUID putawayTaskId, String status, UUID assignedLocationId, Integer stopSequence,
                         boolean holding, Instant loadedAt, Instant placedAt) {
    }

    /**
     * A place the trip still stops at.
     *
     * @param x    where the clerk stands ({@code y} too); null when the map cannot say
     * @param path the line to walk to it, round shelves, walls and closed doors: from the origin for the first stop,
     *             else from the last stop before it that has a point, both ends included. Null when either end has no
     *             point or no way leads there - a client then draws the straight segment (issue #410)
     */
    public record Stop(int sequence, UUID locationId, String locationCode, BigDecimal x, BigDecimal y,
                       List<MapPoint> path) {
        public Stop {
            path = path == null ? null : List.copyOf(path);
        }
    }

    /** A point of the warehouse map, in its unit (metres). */
    public record MapPoint(BigDecimal x, BigDecimal y) {
    }
}
