package com.stockflow.inventory.internal.entity;

import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of one hold. A child of {@code stock_item} — never loaded or saved on its own.
 *
 * <p>The indexes are not decoration; each one exists for a query that runs constantly:
 * {@code order_id} for cancellation, {@code expires_at} for the sweeper's minute-by-minute scan.
 * The unique constraint on {@code request_id} is the last line of defence for idempotency: the
 * aggregate checks in memory, and the database refuses even if two threads slip past that check
 * simultaneously.</p>
 */
@Entity
@Table(
        name = "stock_reservation",
        schema = "inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stock_reservation_request", columnNames = "request_id"),
        indexes = {
                @Index(name = "ix_stock_reservation_order", columnList = "order_id"),
                @Index(name = "ix_stock_reservation_root_request", columnList = "root_request_id"),
                @Index(name = "ix_stock_reservation_expiry", columnList = "status, expires_at")
        })
public class ReservationJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_item_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_reservation_stock_item"))
    private StockItemJpaEntity stockItem;

    /**
     * Reference to an order in another module's schema. A plain UUID column with no foreign key —
     * cross-schema FKs would couple the two modules' migrations and make it impossible to ever
     * move one out. Referential integrity across modules is the application's job.
     */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /**
     * The caller's own idempotency key. Indexed but NOT unique: one reservation call can produce
     * several holds, one per lot the line was drawn from.
     */
    @Column(name = "root_request_id", nullable = false, updatable = false)
    private UUID rootRequestId;

    /** Derived per stock item, so the unique constraint tolerates a line spanning several lots. */
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "reserved_at", nullable = false, updatable = false)
    private Instant reservedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 32)
    private ReleaseReason releaseReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected ReservationJpaEntity() {
    }

    public ReservationJpaEntity(UUID id, UUID orderId, UUID rootRequestId, UUID requestId, int quantity,
                         Instant reservedAt, Instant expiresAt, ReservationStatus status,
                         ReleaseReason releaseReason, Instant closedAt) {
        this.id = id;
        this.orderId = orderId;
        this.rootRequestId = rootRequestId;
        this.requestId = requestId;
        this.quantity = quantity;
        this.reservedAt = reservedAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
    }

    public void attachTo(StockItemJpaEntity parent) {
        this.stockItem = parent;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getRootRequestId() { return rootRequestId; }
    public UUID getRequestId() { return requestId; }
    public int getQuantity() { return quantity; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public ReservationStatus getStatus() { return status; }
    public ReleaseReason getReleaseReason() { return releaseReason; }
    public Instant getClosedAt() { return closedAt; }
}
