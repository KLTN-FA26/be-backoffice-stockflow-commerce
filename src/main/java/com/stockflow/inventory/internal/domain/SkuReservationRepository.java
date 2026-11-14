package com.stockflow.inventory.internal.domain;

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

    /** The live (HELD) reservations a hold request made, for its replay check. Not locked. */
    List<SkuReservation> findHeldForRequest(UUID rootRequestId);

    /** The live reservations of an order, locked, in (SKU, id) order. */
    List<SkuReservation> findHeldForOrderForUpdate(UUID orderId);

    /** The live reservations of an order, read without a lock. */
    List<SkuReservation> findHeldForOrder(UUID orderId);

    /** Whether a reservation of the order closed without being allocated (expired or released). */
    boolean anyLapsedForOrder(UUID orderId);

    /** Units under live SKU reservations, per SKU code; a SKU with none is absent. */
    Map<String, Long> heldBySku(Collection<String> skus);

    /** WF4-COUNT-EDGE: the live SKU reservations of {@code sku} held at {@code warehouseCode}, oldest first. Not locked. */
    List<SkuReservation> findHeldAt(String sku, String warehouseCode);

    /** 4B-7: the live SKU reservations of {@code sku} held at a warehouse, by warehouse code (20 BR-17). */
    Map<String, Long> heldBySkuAt(String sku);

    /** These reservations, in any status; an unknown id is absent. */
    List<SkuReservation> findAll(Collection<UUID> reservationIds);

    SkuReservation save(SkuReservation reservation);

    /**
     * Writes the pending changes now. WF4-COUNT-EDGE: a release must reach the table before the smaller hold replacing
     * it is inserted - a flush inserts before it updates, and {@code tg_stock_reservation_soft_atp} would count both.
     */
    void flush();
}
