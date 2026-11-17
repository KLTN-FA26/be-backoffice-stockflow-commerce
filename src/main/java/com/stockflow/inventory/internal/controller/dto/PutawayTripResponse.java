package com.stockflow.inventory.internal.controller.dto;

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
 * @param stops             empty unless the trip is on its way; the route is advice, not an order to follow
 */
public record PutawayTripResponse(UUID id, UUID warehouseId, UUID operatorId, String status, String startLocationCode,
                                  BigDecimal startX, BigDecimal startY, Instant startedAt, Instant lastActivityAt,
                                  Instant closedAt, UUID closedBy, List<Carton> cartons, List<Stop> stops) {

    /**
     * @param status               {@code ON_CART}, {@code PLACED} or {@code RETURNED}
     * @param assignedLocationCode the bin (or OVERFLOW area) given when the trip set off
     * @param holding              whether its space there is still held (30 minutes without a scan releases it)
     */
    public record Carton(UUID putawayTaskId, String lpn, String sku, String lotNumber, String status,
                         String assignedLocationCode, Integer stopSequence, boolean holding, Instant loadedAt,
                         Instant placedAt) {
    }

    /** One stop: where to stand, and the cartons to put there. */
    public record Stop(int sequence, String locationCode, BigDecimal x, BigDecimal y, List<String> lpns) {
    }
}
