package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The persistence <b>port</b>.
 *
 * <p>It belongs to the domain and speaks the domain's language: no {@code Page}, no
 * {@code Specification}, no {@code Example}, nothing from Spring Data leaks through. The adapter
 * in {@code internal.repository} implements it. That inversion is what lets
 * {@code ReserveStockServiceTest} run against a hand-written in-memory map in milliseconds
 * instead of booting Postgres.</p>
 */
public interface StockItemRepository {

    Optional<StockItem> findById(StockItemId id);

    /**
     * Load for update under a pessimistic write lock.
     *
     * <p>Reservation is a read-then-write: check ATP, then add a hold. Two checkouts running that
     * against the same row will both see enough stock and both reserve it — the classic oversell.
     * A {@code SELECT ... FOR UPDATE} serialises them, so the second one sees the first one's
     * hold. Optimistic {@code @Version} was the alternative and was rejected: under a flash sale
     * it turns every concurrent checkout into a retry storm.</p>
     *
     * <p><b>The implementation must return state read <i>after</i> the lock was taken.</b> A JPA
     * query returns whatever copy of the row is already in the persistence context, lock or no
     * lock, so an adapter that merely adds {@code FOR UPDATE} to a query would serialise the
     * transactions and still hand the second one stale numbers. That would defeat the entire
     * purpose of taking the lock.</p>
     */
    Optional<StockItem> findByIdForUpdate(StockItemId id);

    /**
     * Every stock item holding a live reservation for this caller request.
     *
     * <p>The replay check. It has to be answered before the allocation plan is built: the first
     * attempt's own holds have already reduced availability, so a replanned retry would pick
     * different lots, derive different per-item keys, and reserve the stock a second time.</p>
     */
    List<StockItem> findWithReservationsForRequest(java.util.UUID rootRequestId);

    /**
     * Read-only projections for the allocation planner: enough to choose lots, and nothing more.
     *
     * <p>Returning aggregates here would put them in the persistence context and make the later
     * locked read a cache hit — see the note on {@link #findByIdForUpdate}.</p>
     */
    List<StockAllocator.Candidate> findAvailabilityBySku(Sku sku);

    /** Sellable stock for one SKU across all locations. Used by the query side, never before a lock. */
    List<StockItem> findAvailableBySku(Sku sku);

    List<StockItem> findBySkuAndLocation(Sku sku, LocationId location);

    /** Every stock item holding at least one reservation that expired before {@code cutoff}. */
    List<StockItem> findWithReservationsExpiredBefore(Instant cutoff, int limit);

    /** Locates the aggregate that owns a reservation, given only the id order handed back. */
    Optional<StockItem> findByReservationIdForUpdate(ReservationId reservationId);

    /**
     * Every stock item holding at least one live reservation for this order.
     *
     * <p>One order line can be spread over several lots, so cancelling an order has to reach all
     * of them. Takes a raw {@code UUID} rather than an {@code OrderId} type because {@code order}
     * owns that concept — inventory only stores the reference.</p>
     */
    List<StockItem> findWithReservationsForOrder(java.util.UUID orderId);

    StockItem save(StockItem stockItem);
}
