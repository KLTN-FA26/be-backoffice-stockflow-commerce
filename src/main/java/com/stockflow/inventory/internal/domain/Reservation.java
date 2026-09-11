package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One hold on stock, held by one order line, inside one {@link StockItem}.
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
    private final UUID orderId;
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
    private final Quantity quantity;
    private final Instant reservedAt;
    private final Instant expiresAt;

    private ReservationStatus status;
    private ReleaseReason releaseReason;
    private Instant closedAt;

    /** Full constructor, used by the persistence mapper when rehydrating a stored row. */
    public Reservation(ReservationId id, UUID orderId, UUID rootRequestId, UUID requestId,
                       Quantity quantity, Instant reservedAt, Instant expiresAt,
                       ReservationStatus status, ReleaseReason releaseReason, Instant closedAt) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.orderId = java.util.Objects.requireNonNull(orderId, "orderId");
        this.rootRequestId = java.util.Objects.requireNonNull(rootRequestId, "rootRequestId");
        this.requestId = java.util.Objects.requireNonNull(requestId, "requestId");
        this.quantity = java.util.Objects.requireNonNull(quantity, "quantity");
        this.reservedAt = java.util.Objects.requireNonNull(reservedAt, "reservedAt");
        this.expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
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

    /** True once {@code expiresAt} has passed and nobody has consumed or released the hold. */
    public boolean isExpiredAt(Instant now) {
        return status.isActive() && !now.isBefore(expiresAt);
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

    private void requireActive(String operation) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot %s reservation %s: it is already %s".formatted(operation, id, status));
        }
    }

    public ReservationId id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID rootRequestId() { return rootRequestId; }
    public UUID requestId() { return requestId; }
    public Quantity quantity() { return quantity; }
    public Instant reservedAt() { return reservedAt; }
    public Instant expiresAt() { return expiresAt; }
    public ReservationStatus status() { return status; }
    public ReleaseReason releaseReason() { return releaseReason; }
    public Instant closedAt() { return closedAt; }
}
