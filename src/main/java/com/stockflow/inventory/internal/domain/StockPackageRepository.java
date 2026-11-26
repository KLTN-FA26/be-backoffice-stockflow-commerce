package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The persistence <b>port</b> of {@link StockPackage}, the carton that is the stock (ADR-0011).
 *
 * <p>It belongs to the domain and speaks the domain's language: no {@code Page}, no {@code Specification}, nothing
 * from Spring Data leaks through. The adapter in {@code internal.repository} implements it, which is what lets the
 * service tests run against a hand-written in-memory map instead of booting Postgres.</p>
 */
public interface StockPackageRepository {

    Optional<StockPackage> findById(StockPackageId id);

    Optional<StockPackage> findByLpn(String lpn);

    /** The cartons with these LPNs, in one read; an LPN that is not a carton is absent. */
    List<StockPackage> findByLpns(Collection<String> lpns);

    /** The cartons with these ids, in one read, without locking; an id that is not a carton is absent. */
    List<StockPackage> findAllById(Collection<StockPackageId> ids);

    /** The cartons labelled on these goods receipt lines, read without locking. */
    List<StockPackage> findByReceiptLines(Collection<UUID> goodsReceiptLineIds);

    /**
     * The cartons with these ids, locked {@code FOR UPDATE} in id order and read after the lock.
     *
     * <p>Allocation is a read-then-write: see the carton free, then give it to an order. Two releases running that
     * against one carton would both see it free - the classic oversell. The row lock serialises them, so the second
     * sees the first one's allocation. Optimistic {@code @Version} alone was rejected: under load it turns every
     * concurrent release into a retry storm.</p>
     *
     * <p><b>The implementation must return state read <i>after</i> the lock was taken.</b> A JPA query returns
     * whatever copy of the row is already in the persistence context, lock or no lock, so an adapter that merely adds
     * {@code FOR UPDATE} would serialise the transactions and still hand the second one a stale carton.</p>
     *
     * <p>The lock order every writer of a carton keeps: the SKU advisory lock, then the cartons, in id order. There is
     * nothing to lock after a carton any more (the stock rows went with V20261230000600).</p>
     */
    List<StockPackage> findAllByIdForUpdate(Collection<StockPackageId> ids);

    /** {@link #findAllByIdForUpdate} for one carton. */
    default Optional<StockPackage> findByIdForUpdate(StockPackageId id) {
        return findAllByIdForUpdate(List.of(id)).stream().findFirst();
    }

    StockPackage save(StockPackage stockPackage);

    /**
     * {@link #save}, written to the database at once rather than at the next flush. For a carton that comes back into
     * stock at another place: the ledger line that follows names it there, and {@code check_stock_movement_package}
     * reads the carton's row when that line is inserted - a flush writes inserts before updates, so the line would
     * find the carton where it was.
     */
    StockPackage saveNow(StockPackage stockPackage);

    // ------------------------------------------------------------------ allocations

    /** The carton an allocation is on, without locking it — so the SKU lock can be taken before the carton's. */
    Optional<StockPackageId> findIdByReservationId(ReservationId reservationId);

    /** The carton an allocation is on, read without locking. */
    default Optional<StockPackage> findByReservationId(ReservationId reservationId) {
        return findIdByReservationId(reservationId).flatMap(this::findById);
    }

    /**
     * Every carton holding a live allocation for this caller request, locked in id order.
     *
     * <p>The replay check. It has to be answered before the allocation plan is built: the first attempt's own
     * allocations have already taken their cartons, so a replanned retry would pick others, derive different keys, and
     * allocate a second time.</p>
     */
    List<StockPackage> findWithReservationsForRequest(UUID rootRequestId);

    /** The same cartons, read without locking: for an answer about what a request made, not for a change. */
    List<StockPackage> findHoldingRequest(UUID rootRequestId);

    /**
     * The cartons a request ever allocated, whatever became of its allocations since (picked, released), read without
     * locking: for an answer about what a request made.
     */
    List<StockPackage> findReservedByRequest(UUID rootRequestId);

    /**
     * The live (HELD) allocations of an order with the SKU and location they sit at, read-only and
     * without locking: for a caller that only needs to know where an order's stock is.
     */
    List<HeldStock> findHeldForOrder(UUID orderId);

    /** The allocations with these ids and where they sit, read-only and without locking. */
    List<HoldDetail> findHolds(Collection<UUID> reservationIds);

    // ------------------------------------------------------------------ what is there

    /**
     * The free cartons of a SKU - AVAILABLE, no live allocation - as read-only projections for the allocation
     * planner: enough to choose cartons, and nothing more.
     *
     * <p>Returning aggregates here would put them in the persistence context and make the later locked read a cache
     * hit — see the note on {@link #findAllByIdForUpdate}.</p>
     */
    List<StockAllocator.Candidate> findAvailabilityBySku(Sku sku);

    /** The AVAILABLE cartons of one SKU across all locations, held or not. Used by the query side, never before a lock. */
    List<StockPackage> findAvailableBySku(Sku sku);

    /**
     * The cartons of these SKUs a release could allocate: AVAILABLE and held by no order (team plan 4C-2; kltn-docs 07
     * BR-03). Available to promise is counted from these. Where they stand is the caller's to judge (pickable bins are
     * the warehouse module's). Nothing expires (team plan 5-1).
     */
    List<Allocatable> findAllocatable(Collection<String> skus);

    /** A carton {@link #findAllocatable} returns: its units (its count) at its location. */
    record Allocatable(StockPackageId id, String sku, String locationCode, int units) {
    }

    /**
     * Per (location, stock status) subtotals of one SKU's cartons in stock, in units, every status included; feeds
     * Inventory Level. {@code reserved} is the units of the cartons a live allocation names; each line counts its
     * cartons too.
     */
    List<StockLevelLine> findLevelLinesBySku(Sku sku);

    /**
     * What of one SKU is allocated for picking, per location code: the cartons a live allocation names, in units and
     * counted. The {@code reserved} of {@link #findLevelLinesBySku}, per location.
     */
    Map<String, Allocated> findAllocatedBySku(Sku sku);

    /** Allocated stock at one location: {@code units} of {@code cartons} cartons. */
    record Allocated(int units, int cartons) {
    }

    /**
     * The carton's stock status as the table has it now, read as a scalar - never the copy this transaction may
     * already hold of the carton. Empty when the carton is not in stock, or is no carton.
     */
    Optional<StockStatus> stockStatusOf(StockPackageId id);

    // ------------------------------------------------------------------ returns

    /**
     * Marks the cartons as come back opened on a return (WF-05 TH-4, {@code return_opened_at}): fewer units inside than
     * their label, so they are only ever written off. Set once; marking one again changes nothing. The caller holds the
     * cartons' row locks.
     */
    void markOpenedOnReturn(Collection<StockPackageId> ids, Instant at);

    /**
     * Per warehouse code, how many cartons that came back on a return receipt are in quarantine now (WF-05 BE-24): in
     * stock, QUARANTINE, arrived on a return receipt's ledger line.
     */
    Map<String, Integer> returnedCartonsInQuarantine();

    /** When the carton came back opened on a return ({@link #markOpenedOnReturn}); empty when it never did. */
    Optional<Instant> returnOpenedAt(StockPackageId id);

    /** Whether the carton came back opened on a return ({@link #markOpenedOnReturn}). */
    boolean openedOnReturn(StockPackageId id);

    // ------------------------------------------------------------------ labels and units

    /**
     * The next {@code count} LPNs of {@code day} ({@code LPN-yyyyMMdd-nnnnnn}), consecutive and in order, from
     * {@code platform.document_sequence} type {@code LPN}; {@code day} is the business's calendar day, as every
     * document number's is. Drawn as one block and committed on its own, outside the caller's transaction, so it
     * holds no lock under the caller's SKU lock: a number is never handed out twice, but ones the caller then rolls
     * back are lost (a gap).
     */
    List<String> nextLpns(LocalDate day, int count);

    /**
     * How many units one carton of {@code sku} holds: the factor of its carton unit, its one active packing unit
     * (01 BR-03). Empty when it has none (it then has no stock in cartons either).
     */
    OptionalInt cartonFactorOf(String sku);
}
