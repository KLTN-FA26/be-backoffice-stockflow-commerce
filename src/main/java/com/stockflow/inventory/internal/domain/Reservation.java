package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One allocation: one carton ({@link StockPackage}) held for one order.
 *
 * <p><b>Why this class exists.</b> The first cut of this model kept {@code reserved} as a single
 * integer beside the stock. That number cannot answer two questions the business asks every
 * day: which order holds this, and how do we release exactly that order's hold. An
 * entity with an owner answers both - and since V20261230000600 it is the only record that a carton is taken: the
 * counter is gone, so it cannot drift from these rows. (It once carried a deadline too, for an abandoned checkout; no
 * hold has had one since V20261117000300, and the column went with V20261230000500.)</p>
 *
 * <p><b>Entity, not aggregate root.</b> It has an identity that matters ({@link ReservationId})
 * but no independent life: it is created, consumed and released only through its
 * {@code StockPackage}, which is what keeps a carton to at most one live allocation. Loading
 * a reservation on its own and mutating it would step around that invariant, so there is no
 * repository for this type.</p>
 *
 * <p><b>Mutable by design.</b> Value objects in this module are records; this one is a class with
 * private setters-by-behaviour, because {@code status} genuinely changes over the row's life.</p>
 */
public final class Reservation {

    private final ReservationId id;
    /** The order line's order, for a checkout hold or an allocation. */
    private final UUID orderId;
    /**
     * The caller's own idempotency key, shared by every hold this one reservation call produced.
     *
     * <p>Kept alongside {@link #requestId} rather than instead of it because they answer different
     * questions. This one answers "has this caller's request already been served?", which must be
     * asked <i>before</i> any cartons are chosen. The derived one answers "has this exact carton
     * already been held for it?", and has to differ per carton so the unique constraint does
     * not reject the second carton.</p>
     */
    private final UUID rootRequestId;
    private final UUID requestId;
    /**
     * The count of the one carton this hold is (ADR-0011, V20261230000500): an allocation takes its carton
     * whole and is released whole, so it stores no number of cartons - always one - and no units of its own.
     */
    private final int unitsPerCarton;
    private final Instant reservedAt;

    private ReservationStatus status;
    private ReleaseReason releaseReason;
    private Instant closedAt;
    /**
     * The {@link SkuReservation} this hold was allocated from (F-FUL step 02), null for a checkout hold placed
     * straight on stock before SKU reservations existed (history).
     */
    private final UUID allocatedFrom;

    /** An order's hold, as stored. */
    public Reservation(ReservationId id, UUID orderId, UUID rootRequestId, UUID requestId,
                       int unitsPerCarton, Instant reservedAt,
                       ReservationStatus status, ReleaseReason releaseReason, Instant closedAt) {
        this(id, orderId, rootRequestId, requestId, unitsPerCarton, reservedAt, status, releaseReason, closedAt, null);
    }

    /** Full constructor, used by the persistence mapper when rehydrating a stored row. */
    @SuppressWarnings("java:S107")
    public Reservation(ReservationId id, UUID orderId,
                       UUID rootRequestId, UUID requestId, int unitsPerCarton, Instant reservedAt,
                       ReservationStatus status, ReleaseReason releaseReason, Instant closedAt, UUID allocatedFrom) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        if (status == ReservationStatus.ALLOCATED) {
            throw new IllegalArgumentException("An allocation of a carton is never ALLOCATED; its SKU reservation is");
        }
        this.allocatedFrom = allocatedFrom;
        this.orderId = java.util.Objects.requireNonNull(orderId, "orderId");
        this.rootRequestId = java.util.Objects.requireNonNull(rootRequestId, "rootRequestId");
        this.requestId = java.util.Objects.requireNonNull(requestId, "requestId");
        this.unitsPerCarton = unitsPerCarton;
        this.reservedAt = java.util.Objects.requireNonNull(reservedAt, "reservedAt");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
        if (unitsPerCarton < 1) {
            throw new IllegalArgumentException("A reservation of zero units is meaningless");
        }
    }

    /**
     * An allocation (F-FUL step 02): one carton of a {@link SkuReservation}, given to the
     * order when it was released to its warehouse. Package-private: only {@link StockPackage} places one.
     */
    static Reservation allocate(UUID orderId, UUID allocatedFrom, UUID rootRequestId, UUID requestId,
                                int unitsPerCarton, Instant now) {
        return new Reservation(new ReservationId(com.stockflow.common.id.Identifiers.newId()), orderId,
                rootRequestId, requestId, unitsPerCarton, now, ReservationStatus.HELD, null, null,
                java.util.Objects.requireNonNull(allocatedFrom, "allocatedFrom"));
    }

    void consume(Instant now) {
        requireActive("consume");
        this.status = ReservationStatus.CONSUMED;
        this.closedAt = now;
    }

    void release(ReleaseReason reason, Instant now) {
        requireActive("release");
        this.status = reason == ReleaseReason.RESERVATION_EXPIRED
                ? ReservationStatus.EXPIRED
                : ReservationStatus.RELEASED;
        this.releaseReason = reason;
        this.closedAt = now;
    }

    /**
     * A closed hold cannot be consumed or released again. A business refusal (409
     * {@code RESERVATION_NOT_ACTIVE}), not a programming error: a picker scanning goods whose order was
     * just cancelled, or a hold released by hand, reaches this through an ordinary request.
     */
    private void requireActive(String operation) {
        if (status.isTerminal()) {
            throw new com.stockflow.common.error.BusinessException(
                    com.stockflow.common.error.ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Cannot %s reservation %s: it is already %s".formatted(operation, id, status));
        }
    }

    public ReservationId id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID rootRequestId() { return rootRequestId; }
    public UUID requestId() { return requestId; }
    /** The count of the carton held; the hold is always that one carton. */
    public int unitsPerCarton() { return unitsPerCarton; }
    /** The units the hold stands for, derived: its one carton's count. Nothing stores it. */
    public Quantity quantity() { return Quantity.of(unitsPerCarton); }
    public Instant reservedAt() { return reservedAt; }
    public ReservationStatus status() { return status; }
    public ReleaseReason releaseReason() { return releaseReason; }
    public Instant closedAt() { return closedAt; }
    public UUID allocatedFrom() { return allocatedFrom; }

    /** Whether this hold is an allocation of a SKU reservation rather than a pre-split checkout hold. */
    public boolean isAllocation() { return allocatedFrom != null; }
}
