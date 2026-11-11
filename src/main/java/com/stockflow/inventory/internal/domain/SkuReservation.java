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
 * <p><b>Why it is not a {@link Reservation}.</b> A {@code Reservation} lives inside one {@link StockItem} and is
 * what a picker walks to. Choosing that stock item when the hold is taken — what this module did until SCRUM-152 — picked a
 * bin and a lot days before anybody knew which warehouse would ship the order, so FEFO was decided on stale data
 * and the coordinator's release could only discover afterwards that the stock sat in the wrong building. The
 * choice now happens at release ({@code InventoryService.allocate}): this hold turns {@code ALLOCATED} and lives
 * on as one {@code Reservation} per bin and lot, each pointing back here through {@code allocatedFrom}.</p>
 *
 * <p><b>Its own aggregate.</b> It belongs to no stock item, so nothing else can keep its invariants; the one rule
 * that spans many of them — the SKU's reservations never exceed its sellable stock — is checked by the application
 * service under the SKU's advisory lock and again by {@code tg_stock_reservation_soft_atp} in the database.</p>
 *
 * <p>Stored in {@code inventory.stock_reservation} with {@code stock_item_id IS NULL}: the order line keeps the
 * same reservation ids it always kept, and {@code ordering.order_line_reservation}'s foreign key keeps pointing at
 * the same table.</p>
 */
public final class SkuReservation extends AggregateRoot {

    private final ReservationId id;
    private final Sku sku;
    private final UUID orderId;
    private final UUID rootRequestId;
    private final UUID requestId;
    private final Quantity quantity;
    private final Instant reservedAt;
    /** Null once pinned: the order no longer waits for payment (SCRUM-465). */
    private Instant expiresAt;
    private ReservationStatus status;
    private ReleaseReason releaseReason;
    private Instant closedAt;
    /**
     * 4B-7 (20 §5.6, BR-17): the warehouse the hold is at - an order of a contract holds its stock at its shipping
     * warehouse only. Null on a hold of before 4B-7, promised against the whole system.
     */
    private final String warehouseCode;

    public SkuReservation(ReservationId id, Sku sku, UUID orderId, UUID rootRequestId, UUID requestId,
                          Quantity quantity, Instant reservedAt, Instant expiresAt, ReservationStatus status,
                          ReleaseReason releaseReason, Instant closedAt) {
        this(id, sku, orderId, rootRequestId, requestId, quantity, reservedAt, expiresAt, status, releaseReason,
                closedAt, null);
    }

    public SkuReservation(ReservationId id, Sku sku, UUID orderId, UUID rootRequestId, UUID requestId,
                          Quantity quantity, Instant reservedAt, Instant expiresAt, ReservationStatus status,
                          ReleaseReason releaseReason, Instant closedAt, String warehouseCode) {
        this.warehouseCode = warehouseCode;
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.rootRequestId = Objects.requireNonNull(rootRequestId, "rootRequestId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.reservedAt = Objects.requireNonNull(reservedAt, "reservedAt");
        this.expiresAt = expiresAt;
        this.status = Objects.requireNonNull(status, "status");
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
        if (quantity.isZero()) {
            throw new IllegalArgumentException("A reservation of zero units is meaningless");
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
     * bin, and with no deadline: it lasts until release, cancellation or a manual release. {@code available} is the
     * SKU's available-to-promise at that warehouse, read under the SKU's advisory lock.
     *
     * @throws InsufficientStockException when fewer than {@code quantity} units are available to promise there
     */
    public static SkuReservation holdAt(String warehouseCode, Sku sku, UUID orderId, UUID requestId, Quantity quantity,
                                        int available, Instant now) {
        Objects.requireNonNull(warehouseCode, "warehouseCode");
        if (available < quantity.value()) {
            throw new InsufficientStockException(sku.code(), quantity.value(), Math.max(available, 0));
        }
        SkuReservation reservation = new SkuReservation(new ReservationId(Identifiers.newId()), sku, orderId,
                requestId, requestId, quantity, now, null, ReservationStatus.HELD, null, null, warehouseCode);
        reservation.registerEvent(new StockEvent.Reserved(new StockReserved(orderId, sku.code(), null,
                quantity.value(), null)));
        return reservation;
    }

    /** True once {@code expiresAt} has passed and nobody has allocated or released the hold. */
    public boolean isExpiredAt(Instant now) {
        return status.isActive() && expiresAt != null && !now.isBefore(expiresAt);
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
        registerEvent(new StockEvent.Released(new StockReservationReleased(orderId, sku.code(), quantity.value(),
                reason.toContract())));
        return true;
    }

    /**
     * The order was released to a warehouse and every unit of this hold now sits in an allocation (F-FUL step 02).
     * Called after the allocations were made, in the same transaction, so the units are never counted twice: from
     * here on they are in {@code stock_item.reserved}, no longer in the SKU's soft reservations.
     *
     * @throws BusinessException {@code RESERVATION_NOT_ACTIVE} when the hold was released or expired meanwhile
     */
    public void markAllocated(Instant now) {
        if (status.isTerminal()) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation %s is %s and cannot be allocated".formatted(id, status));
        }
        this.status = ReservationStatus.ALLOCATED;
        this.expiresAt = null;
        this.closedAt = now;
    }

    public ReservationId id() { return id; }
    public Sku sku() { return sku; }
    public UUID orderId() { return orderId; }
    public UUID rootRequestId() { return rootRequestId; }
    public UUID requestId() { return requestId; }
    public Quantity quantity() { return quantity; }
    public Instant reservedAt() { return reservedAt; }
    public Instant expiresAt() { return expiresAt; }
    public ReservationStatus status() { return status; }
    public ReleaseReason releaseReason() { return releaseReason; }
    public Instant closedAt() { return closedAt; }
    public String warehouseCode() { return warehouseCode; }
}
