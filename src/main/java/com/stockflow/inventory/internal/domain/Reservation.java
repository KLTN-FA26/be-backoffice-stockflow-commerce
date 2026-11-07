package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One hold on stock, held by one order line — or by one transfer order — inside one {@link StockItem}.
 *
 * <p><b>Why this class exists.</b> The first cut of this model kept {@code reserved} as a single
 * integer on the stock item. That number cannot answer three questions the business asks every
 * day: how much did <i>this</i> order reserve, how do we release exactly that order's share, and
 * what happens when a customer abandons checkout — the stock stayed locked forever. An entity
 * with an owner and an expiry answers all three.</p>
 *
 * <p><b>Entity, not aggregate root.</b> It has an identity that matters ({@link ReservationId})
 * but no independent life: it is created, consumed and released only through its
 * {@code StockItem}, which is what keeps {@code sum(active reservations) <= onHand} true. Loading
 * a reservation on its own and mutating it would step around that invariant, so there is no
 * repository for this type.</p>
 *
 * <p><b>Mutable by design.</b> Value objects in this module are records; this one is a class with
 * private setters-by-behaviour, because {@code status} genuinely changes over the row's life.</p>
 */
public final class Reservation {

    private final ReservationId id;
    /** The order line's order, for a checkout hold or an allocation; null for a transfer's hold. */
    private final UUID orderId;
    /**
     * The transfer order, for stock allocated to a transfer at its source (SCRUM-287 PR T; kltn-docs 10 step 4, BR-02/03):
     * no order may sell it. Exactly one of this and {@link #orderId} is set. It does not expire - there is no payment to
     * wait for - so it is opened pinned; consumed when the transfer is dispatched.
     */
    private final UUID transferOrderId;
    /**
     * The caller's own idempotency key, shared by every hold this one reservation call produced.
     *
     * <p>Kept alongside {@link #requestId} rather than instead of it because they answer different
     * questions. This one answers "has this caller's request already been served?", which must be
     * asked <i>before</i> any lots are chosen. The derived one answers "has this exact stock item
     * already been held for it?", and has to differ per stock item so the unique constraint does
     * not reject the second lot.</p>
     */
    private final UUID rootRequestId;
    private final UUID requestId;
    /** Fixed when the hold is made; lowered only when part of it is taken off a damaged row (stock damage). */
    private Quantity quantity;
    private final Instant reservedAt;
    /** Null once pinned: the order left PENDING_PAYMENT and the hold no longer expires (SCRUM-465). */
    private Instant expiresAt;

    private ReservationStatus status;
    private ReleaseReason releaseReason;
    private Instant closedAt;
    /**
     * The {@link SkuReservation} this hold was allocated from (F-FUL step 02), null for a transfer's hold and for a
     * checkout hold placed on a stock item before SKU reservations existed. An allocation never expires.
     */
    private final UUID allocatedFrom;

    /** An order's hold, as stored. */
    public Reservation(ReservationId id, UUID orderId, UUID rootRequestId, UUID requestId,
                       Quantity quantity, Instant reservedAt, Instant expiresAt,
                       ReservationStatus status, ReleaseReason releaseReason, Instant closedAt) {
        this(id, java.util.Objects.requireNonNull(orderId, "orderId"), null, rootRequestId, requestId, quantity,
                reservedAt, expiresAt, status, releaseReason, closedAt, null);
    }

    /**
     * Full constructor, used by the persistence mapper when rehydrating a stored row. Exactly one owner: an order or a
     * transfer order (SCRUM-287 PR T). A transfer's hold is never an allocation of a SKU reservation and never expires.
     */
    @SuppressWarnings("java:S107")
    public Reservation(ReservationId id, UUID orderId, UUID transferOrderId,
                       UUID rootRequestId, UUID requestId, Quantity quantity, Instant reservedAt, Instant expiresAt,
                       ReservationStatus status, ReleaseReason releaseReason, Instant closedAt, UUID allocatedFrom) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        if (status == ReservationStatus.ALLOCATED) {
            throw new IllegalArgumentException("A hold on a stock item is never ALLOCATED; its SKU reservation is");
        }
        if (allocatedFrom != null && (orderId == null || expiresAt != null)) {
            throw new IllegalArgumentException("An allocation belongs to an order and does not expire");
        }
        this.allocatedFrom = allocatedFrom;
        if ((orderId == null) == (transferOrderId == null)) {
            throw new IllegalArgumentException("A hold belongs to exactly one order or one transfer order");
        }
        if (transferOrderId != null && expiresAt != null) {
            throw new IllegalArgumentException("A transfer hold does not expire");
        }
        this.orderId = orderId;
        this.transferOrderId = transferOrderId;
        this.rootRequestId = java.util.Objects.requireNonNull(rootRequestId, "rootRequestId");
        this.requestId = java.util.Objects.requireNonNull(requestId, "requestId");
        this.quantity = java.util.Objects.requireNonNull(quantity, "quantity");
        this.reservedAt = java.util.Objects.requireNonNull(reservedAt, "reservedAt");
        this.expiresAt = expiresAt;
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
        if (quantity.isZero()) {
            throw new IllegalArgumentException("A reservation of zero units is meaningless");
        }
    }

    /** Factory for a brand-new hold. Package-private: only {@link StockItem} may open one. */
    static Reservation open(UUID orderId, UUID rootRequestId, UUID requestId, Quantity quantity,
                            Instant now, java.time.Duration ttl) {
        return new Reservation(ReservationId.newId(), orderId, rootRequestId, requestId, quantity,
                now, now.plus(ttl), ReservationStatus.HELD, null, null);
    }

    /**
     * An allocation (F-FUL step 02): units of a {@link SkuReservation} placed on this stock item when the order was
     * released to its warehouse. Pinned - an allocation has no {@code expires_at} (X-RESV). Package-private, like
     * {@link #open}.
     */
    static Reservation allocate(UUID orderId, UUID allocatedFrom, UUID rootRequestId, UUID requestId,
                                Quantity quantity, Instant now) {
        return new Reservation(new ReservationId(com.stockflow.common.id.Identifiers.newId()), orderId, null,
                rootRequestId, requestId, quantity, now, null, ReservationStatus.HELD, null, null,
                java.util.Objects.requireNonNull(allocatedFrom, "allocatedFrom"));
    }

    /** A transfer order's hold (SCRUM-287 PR T): pinned from the start. Package-private, like {@link #open}. */
    static Reservation openForTransfer(UUID transferOrderId, UUID rootRequestId, UUID requestId, Quantity quantity,
                                       Instant now) {
        return new Reservation(ReservationId.newId(), null,
                java.util.Objects.requireNonNull(transferOrderId, "transferOrderId"), rootRequestId, requestId,
                quantity, now, null, ReservationStatus.HELD, null, null, null);
    }

    /** True once {@code expiresAt} has passed and nobody has consumed or released the hold. */
    public boolean isExpiredAt(Instant now) {
        return status.isActive() && expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Keep the hold until the stock is consumed or the order releases it (kltn-docs 14 BR-07: a hold
     * has a deadline only while its order waits for payment). Idempotent.
     *
     * @return true if this call pinned an active hold; false if it was already pinned or is closed
     */
    boolean pin() {
        if (!status.isActive() || expiresAt == null) {
            return false;
        }
        this.expiresAt = null;
        return true;
    }

    /**
     * The order is waiting for payment again (a credit order refused and switched to prepaid or
     * deposit, SCRUM-427): a pinned hold gets a deadline, as at checkout (kltn-docs 14 BR-07).
     *
     * @return true if this call gave an active pinned hold its deadline
     */
    boolean rearm(Instant deadline) {
        if (!status.isActive() || expiresAt != null || transferOrderId != null || allocatedFrom != null) {
            return false;
        }
        this.expiresAt = java.util.Objects.requireNonNull(deadline, "deadline");
        return true;
    }

    public boolean isPinned() {
        return expiresAt == null;
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

    /** The hold covers {@code by} fewer units, keeping its id; a hold of zero is a release, not this. */
    void reduceBy(Quantity by) {
        requireActive("reduce");
        if (by.isZero() || !by.isLessThan(quantity)) {
            throw new IllegalArgumentException("A hold of %s cannot be reduced by %s".formatted(quantity, by));
        }
        this.quantity = quantity.minus(by);
    }

    /**
     * A closed hold cannot be consumed or released again. A business refusal (409
     * {@code RESERVATION_NOT_ACTIVE}), not a programming error: a picker scanning goods whose order was
     * just cancelled, or a hold the sweeper expired, reaches this through an ordinary request.
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
    public UUID transferOrderId() { return transferOrderId; }
    public UUID rootRequestId() { return rootRequestId; }
    public UUID requestId() { return requestId; }
    public Quantity quantity() { return quantity; }
    public Instant reservedAt() { return reservedAt; }
    public Instant expiresAt() { return expiresAt; }
    public ReservationStatus status() { return status; }
    public ReleaseReason releaseReason() { return releaseReason; }
    public Instant closedAt() { return closedAt; }
    public UUID allocatedFrom() { return allocatedFrom; }

    /** Whether this hold is an allocation of a SKU reservation rather than a transfer's or pre-split checkout hold. */
    public boolean isAllocation() { return allocatedFrom != null; }
}
