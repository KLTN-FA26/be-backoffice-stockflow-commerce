package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** The same stock items, read without locking: for an answer about what a request made, not for a change. */
    List<StockItem> findHoldingRequest(java.util.UUID rootRequestId);

    /**
     * Read-only projections for the allocation planner: enough to choose lots, and nothing more.
     *
     * <p>Returning aggregates here would put them in the persistence context and make the later
     * locked read a cache hit — see the note on {@link #findByIdForUpdate}.</p>
     */
    List<StockAllocator.Candidate> findAvailabilityBySku(Sku sku);

    /** Sellable stock for one SKU across all locations. Used by the query side, never before a lock. */
    List<StockItem> findAvailableBySku(Sku sku);

    /**
     * The stock rows of these SKUs a release could allocate: AVAILABLE, each the whole content of a sealed carton in
     * stock that nothing holds (team plan 4C-2; kltn-docs 07 BR-03). Available to promise is counted from these, so
     * checkout never promises loose units or an opened carton that no release would hand out. Where they stand is the
     * caller's to judge (pickable bins are the warehouse module's). Nothing expires (team plan 5-1).
     */
    List<Allocatable> findAllocatable(Collection<String> skus);

    /** A row {@link #findAllocatable} returns: the carton's units at its location. */
    record Allocatable(StockItemId id, String sku, String locationCode, int units) {
    }

    /** Per (location, status) subtotals of one SKU, all statuses included; feeds Inventory Level. */
    List<StockLevelLine> findLevelLinesBySku(Sku sku);

    /**
     * Units of one SKU allocated for picking, per location code: live holds made from a SKU reservation at release.
     * They are part of the {@code reserved} of {@link #findLevelLinesBySku}; Inventory Level shows them apart.
     */
    Map<String, Integer> findAllocatedBySku(Sku sku);


    /**
     * The loose stock rows of {@code sku} at {@code location}: rows in a stock package are left out. A row in a
     * carton is keyed by the carton, not the bin (V20261020000200), and loose units merged into an unopened one
     * would break its counted quantity at commit.
     *
     * <p>There is deliberately no lookup of "every row of a SKU at a bin": a bin may hold a loose layer and
     * several cartons of the very same receipt, and a caller that found one by (SKU, bin, lot, receipt time)
     * would take or fill the others by chance. Stock in a carton is reached through its carton.</p>
     */
    List<StockItem> findLooseBySkuAndLocation(Sku sku, LocationId location);

    /**
     * What is left in a stock package: the sum of its rows' {@code on_hand}, as this transaction has written them
     * (pending changes are flushed first). A carton holds no quantity of its own (V20261020000100).
     */
    int onHandInStockPackage(StockPackageId stockPackageId);

    /** The ids of the stock rows of a stock package (its contents), read without locking (SCRUM-287 PR T, LP2-8). */
    List<StockItemId> findIdsInStockPackage(StockPackageId stockPackageId);

    /**
     * The ids of the rows of several stock packages, in id order - the order rows are locked in - read without
     * locking, in one query: what moving a line's cartons together locks (SCRUM-473).
     */
    List<StockItemId> findIdsInStockPackages(Collection<StockPackageId> stockPackageIds);

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

    /** The stock item a reservation is on, without locking it — so it can be locked in id order with another. */
    Optional<StockItemId> findIdByReservationId(ReservationId reservationId);


    /**
     * The live (HELD) reservations of an order with the SKU and location they sit at, read-only and
     * without locking: for a caller that only needs to know where an order's stock is.
     */
    List<HeldStock> findHeldForOrder(java.util.UUID orderId);

    /** The holds with these ids and where they sit, read-only and without locking. */
    List<HoldDetail> findHolds(Collection<java.util.UUID> reservationIds);

    StockItem save(StockItem stockItem);

    /**
     * {@link #save}, written to the database at once rather than at the next flush. For units leaving a row for a
     * new one: the database checks a serial number's single live row ({@code uk_stock_item_live_serial}) statement by
     * statement, and a flush inserts new rows before it updates old ones - the serial would briefly be on two rows.
     */
    StockItem saveNow(StockItem stockItem);

    /**
     * Save a layer that has just taken units of <b>its own receipt</b> back: from another location (a move, a
     * hold handed over) or from a pick of an order cancelled afterwards (a put-back, into the very row the goods
     * left or into their layer at another bin). The database refuses to raise an existing FIFO layer's
     * {@code on_hand} — replenishment must be a new layer, or FIFO loses the age of the incoming units — and
     * this tells it, for this row and this statement only, that nothing new is entering: the units carry the
     * same receipt time, lot and serial, which the database checks as before.
     */
    StockItem saveMerged(StockItem stockItem);
}
