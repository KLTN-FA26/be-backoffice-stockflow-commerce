package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port of {@link SkuReservation}. Locking reads take a row lock ({@code FOR UPDATE}) and return
 * state read after it, for the same reason {@link StockItemRepository#findByIdForUpdate} does.
 */
public interface SkuReservationRepository {

    /** Whether {@code reservationId} is a SKU reservation (in any status), without loading it. */
    boolean exists(ReservationId reservationId);

    Optional<SkuReservation> findById(ReservationId reservationId);

    Optional<SkuReservation> findByIdForUpdate(ReservationId reservationId);

    /** The live (HELD) reservations a checkout request made, for its replay check. Not locked. */
    List<SkuReservation> findHeldForRequest(UUID rootRequestId);

    /** The live reservations of an order, locked, in (SKU, id) order. */
    List<SkuReservation> findHeldForOrderForUpdate(UUID orderId);

    /** The live reservations of an order, read without a lock. */
    List<SkuReservation> findHeldForOrder(UUID orderId);

    /** Live reservations past {@code cutoff}, locked, skipping rows another transaction holds. */
    List<SkuReservation> findExpiredForUpdate(Instant cutoff, int limit);

    /** Units under live SKU reservations, per SKU code; a SKU with none is absent. */
    Map<String, Long> heldBySku(Collection<String> skus);

    /** These reservations, in any status; an unknown id is absent. */
    List<SkuReservation> findAll(Collection<UUID> reservationIds);

    SkuReservation save(SkuReservation reservation);
}
