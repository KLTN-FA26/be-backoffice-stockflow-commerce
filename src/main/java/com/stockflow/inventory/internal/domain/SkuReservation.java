package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>Reserved</b>, in the glossary's sense (docs 01): the soft hold Sales takes against a SKU at an order's shipping
 * warehouse (kltn-docs 17 §4.2), tied to no bin and no lot. It keeps the warehouse from promising the same cartons
 * twice; it says nothing about which shelf the goods will come from. Since V20261117000300 no hold carries a
 * deadline: the checkout's expiring reservation and its sweeper are gone.
 *
 * <p><b>It counts cartons (ADR-0011, V20261230000500).</b> Stock is whole sealed cartons, so a hold is
 * {@link #cartons()} cartons of {@link #unitsPerCarton()} - the count the order line was quoted in - and is covered,
 * and at release served, only by cartons of exactly that count: which cartons a hold wants is said by their count, not
 * by a packing unit's id, because retired packing units of one item may share a factor. {@link #quantity()} is the
 * units that stands for, derived, for the events and the other modules that still do their arithmetic in units.</p>
 *
 * <p><b>Why it is not a {@link Reservation}.</b> A {@code Reservation} lives inside one {@link StockPackage} and is
 * what a picker walks to. Choosing that carton when the hold is taken — what this module did until SCRUM-152 — picked a
 * bin and a lot days before anybody knew which warehouse would ship the order, so FEFO was decided on stale data
 * and the coordinator's release could only discover afterwards that the stock sat in the wrong building. The
 * choice now happens at release ({@code InventoryService.allocate}): this hold turns {@code ALLOCATED} and lives
 * on as one {@code Reservation} per bin and lot, each pointing back here through {@code allocatedFrom}.</p>
 *
 * <p><b>Its own aggregate.</b> It belongs to no carton, so nothing else can keep its invariants; the one rule
 * that spans many of them — the SKU's reservations never exceed its sellable stock — is checked by the application
 * service under the SKU's advisory lock and again by {@code tg_stock_reservation_soft_atp} in the database.</p>
 *
 * <p>Stored in {@code inventory.stock_reservation} with {@code stock_package_id IS NULL}: the order line keeps the
 * same reservation ids it always kept, and {@code ordering.order_line_reservation}'s foreign key keeps pointing at
 * the same table.</p>
 */
public final class SkuReservation extends AggregateRoot {

    private final ReservationId id;
    private final Sku sku;
    private final UUID orderId;
    private final UUID rootRequestId;
    private final UUID requestId;
    private final int cartons;
    private final int unitsPerCarton;
    private final Instant reservedAt;
    private ReservationStatus status;
    private ReleaseReason releaseReason;
    private Instant closedAt;
    /**
     * 4B-7 (20 §5.6, BR-17): the warehouse the hold is at - an order of a contract holds its stock at its shipping
     * warehouse only. Null on a hold of before 4B-7, promised against the whole system.
     */
    private final String warehouseCode;

    /** A hold, as stored. */
    @SuppressWarnings("java:S107")
    public SkuReservation(ReservationId id, Sku sku, UUID orderId, UUID rootRequestId, UUID requestId,
                          int cartons, int unitsPerCarton, Instant reservedAt, ReservationStatus status,
                          ReleaseReason releaseReason, Instant closedAt, String warehouseCode) {
        this.warehouseCode = warehouseCode;
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.rootRequestId = Objects.requireNonNull(rootRequestId, "rootRequestId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.cartons = cartons;
        this.unitsPerCarton = unitsPerCarton;
        this.reservedAt = Objects.requireNonNull(reservedAt, "reservedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
        if (cartons < 1) {
            throw new IllegalArgumentException("A reservation of no carton is meaningless");
        }
        if (unitsPerCarton < 1) {
            throw new IllegalArgumentException("A hold is of cartons of at least one unit, not " + unitsPerCarton);
        }
        if (status == ReservationStatus.CONSUMED) {
            throw new IllegalArgumentException("A SKU reservation is allocated, never consumed directly");
        }
        if ((status == ReservationStatus.HELD) != (closedAt == null)) {
            throw new IllegalArgumentException("Only a live reservation has no closing time");
        }
    }

    /**
     * 4B-7 (17 §4.2 step 3, 20 §5.6): the hold an order of a contract takes at its shipping warehouse - by SKU, on no
     * bin, and with no deadline: it lasts until release, cancellation or a manual release. {@code availableCartons} is
     * how many cartons of {@code unitsPerCarton} of the SKU are still to promise at that warehouse, read under the SKU's
     * advisory lock: cartons of another count do not serve this hold, so they are not counted.
     *
     * @throws InsufficientStockException when fewer than {@code cartons} such cartons are available to promise there
     *                                    (it speaks units, as every shortage of the module does)
     */
    public static SkuReservation holdAt(String warehouseCode, Sku sku, UUID orderId, UUID requestId, int cartons,
                                        int unitsPerCarton, int availableCartons, Instant now) {
        Objects.requireNonNull(warehouseCode, "warehouseCode");
        if (availableCartons < cartons) {
            throw new InsufficientStockException(sku.code(), cartons * unitsPerCarton,
                    Math.max(availableCartons, 0) * unitsPerCarton);
        }
        SkuReservation reservation = new SkuReservation(new ReservationId(Identifiers.newId()), sku, orderId,
                requestId, requestId, cartons, unitsPerCarton, now, ReservationStatus.HELD, null, null, warehouseCode);
        reservation.registerEvent(new StockEvent.Reserved(new StockReserved(orderId, sku.code(), null,
                reservation.quantity().value(), null)));
        return reservation;
    }

    /**
     * WF4-COUNT-EDGE (a): what is left of this hold, at a warehouse, once stock no longer covers all of it - a new hold of
     * {@code keptCartons} cartons of the same count at the same warehouse for the same order, made after this one was
     * released. Not checked against what is available: it holds less than the hold it replaces, under the same SKU lock.
     *
     * @throws IllegalStateException when this hold is still live, or held at no warehouse
     * @throws IllegalArgumentException when {@code keptCartons} is not between zero and this hold's cartons
     */
    public SkuReservation keeping(int keptCartons, UUID requestId, Instant now) {
        if (status.isActive() || warehouseCode == null) {
            throw new IllegalStateException("Only a released hold at a warehouse is replaced by a smaller one");
        }
        if (keptCartons <= 0 || keptCartons >= cartons) {
            throw new IllegalArgumentException("A smaller hold keeps between 0 and %d carton(s), not %d"
                    .formatted(cartons, keptCartons));
        }
        SkuReservation kept = new SkuReservation(new ReservationId(Identifiers.newId()), sku, orderId, requestId,
                requestId, keptCartons, unitsPerCarton, now, ReservationStatus.HELD, null, null, warehouseCode);
        kept.registerEvent(new StockEvent.Reserved(new StockReserved(orderId, sku.code(), null,
                kept.quantity().value(), null)));
        return kept;
    }

    /**
     * Whether {@code units} is a whole number of this hold's cartons: the module's unit-taking entry points
     * ({@code InventoryService.shrinkHold}) refuse a figure that is not, since a hold cannot keep part of a carton.
     */
    public boolean isWholeCartons(int units) {
        return units % unitsPerCarton == 0;
    }

    /**
     * Give the hold up. A closed hold is left as it is — a cancellation and a release by hand can race on the same
     * row, and neither should fail because the other got there first.
     *
     * @return whether this call closed it
     */
    public boolean release(ReleaseReason reason, Instant now) {
        if (status.isTerminal()) {
            return false;
        }
        this.status = reason == ReleaseReason.RESERVATION_EXPIRED ? ReservationStatus.EXPIRED : ReservationStatus.RELEASED;
        this.releaseReason = reason;
        this.closedAt = now;
        registerEvent(new StockEvent.Released(new StockReservationReleased(orderId, sku.code(), quantity().value(),
                reason.toContract())));
        return true;
    }

    /**
     * The order was released to a warehouse and every carton of this hold now is an allocation (F-FUL step 02).
     * Called after the allocations were made, in the same transaction, so the cartons are never counted twice: from
     * here on each is the live allocation of its carton, no longer in the SKU's soft reservations.
     *
     * @throws BusinessException {@code RESERVATION_NOT_ACTIVE} when the hold was released or expired meanwhile
     */
    public void markAllocated(Instant now) {
        if (status.isTerminal()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation %s is %s and cannot be allocated".formatted(id, status));
        }
        this.status = ReservationStatus.ALLOCATED;
        this.closedAt = now;
    }

    public ReservationId id() { return id; }
    public Sku sku() { return sku; }
    public UUID orderId() { return orderId; }
    public UUID rootRequestId() { return rootRequestId; }
    public UUID requestId() { return requestId; }
    /** How many cartons are held. */
    public int cartons() { return cartons; }
    /** The count of the cartons held: only cartons whose {@code nominal_qty} is this serve the hold. */
    public int unitsPerCarton() { return unitsPerCarton; }
    /** The units the hold stands for, derived: {@code cartons * unitsPerCarton}. Nothing stores it. */
    public Quantity quantity() { return Quantity.of(Math.multiplyExact(cartons, unitsPerCarton)); }
    public Instant reservedAt() { return reservedAt; }
    public ReservationStatus status() { return status; }
    public ReleaseReason releaseReason() { return releaseReason; }
    public Instant closedAt() { return closedAt; }
    public String warehouseCode() { return warehouseCode; }
}
