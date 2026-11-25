package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A putaway trip as the clerk's screen needs it (kltn-docs 05 §4.5): the cart, the bin each carton was given, and the
 * stops still to visit, in order, with where to stand on the warehouse map.
 *
 * @param status            {@code LOADING}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code CLOSED}
 * @param startLocationCode the source area the route starts from; null until the trip set off
 * @param startX            the start of the route on the map; null when the map cannot say
 * @param originLocationCode where the stops still to visit were ordered from: the bin of the last carton put away,
 *                           else the start area. Its point ({@code originX}, {@code originY}) is the first point of
 *                           the first stop's {@code path}; null unless the trip is on its way
 * @param stops             empty unless the trip is on its way; the route is advice, not an order to follow
 */
public record PutawayTripResponse(UUID id, UUID warehouseId, UUID operatorId, String status, String startLocationCode,
                                  BigDecimal startX, BigDecimal startY,
                                  @Schema(description = "Where the remaining stops were ordered from: the last placed"
                                          + " carton's bin, else the start area", example = "BDG-A02-1-A")
                                  String originLocationCode,
                                  @Schema(description = "The origin's point on the map (m); equals stops[0].path[0]")
                                  BigDecimal originX,
                                  BigDecimal originY, Instant startedAt, Instant lastActivityAt,
                                  Instant closedAt, UUID closedBy, List<Carton> cartons, List<Stop> stops,
                                  @Schema(description = "Display name of operatorId", nullable = true)
                                  String operatorName,
                                  @Schema(description = "Display name of closedBy; null while open", nullable = true)
                                  String closedByName,
                                  @Schema(description = "The warehouse's code (its location prefix)", example = "HCM",
                                          nullable = true)
                                  String warehouseCode,
                                  @Schema(nullable = true) String warehouseName) {

    /**
     * @param status               {@code ON_CART}, {@code PLACED} or {@code RETURNED}
     * @param assignedLocationCode the bin (or OVERFLOW area) given when the trip set off
     * @param holding              whether its space there is still held (30 minutes without a scan releases it)
     */
    public record Carton(UUID putawayTaskId, String lpn, String sku, String lotNumber, String status,
                         String assignedLocationCode, Integer stopSequence, boolean holding, Instant loadedAt,
                         Instant placedAt) {
    }

    /**
     * One stop: where to stand, the cartons to put there, and the line to walk to it.
     *
     * @param path the walking line from where the clerk is before this stop - the origin for the first, else the
     *             last stop before it that has a point - to this stop's {@code x}/{@code y}, both ends included,
     *             round shelves, walls and closed doors (issue #410). An estimate from the map: aisles are not
     *             declared. Null when either end has no point or no way leads there; draw the straight segment then
     */
    public record Stop(int sequence, String locationCode, BigDecimal x, BigDecimal y, List<String> lpns,
                       @Schema(description = "Walking polyline to this stop in map units (m), both ends included;"
                               + " null when none can be drawn - fall back to the straight segment",
                               nullable = true)
                       List<Point> path) {
    }

    /** A point of the warehouse map, in metres. */
    public record Point(BigDecimal x, BigDecimal y) {
    }
}
