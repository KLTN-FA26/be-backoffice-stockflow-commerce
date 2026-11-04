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
 * JPA mapping of one hold. Two kinds share the table (SCRUM-152): a hold on a stock item — an allocation, a
 * production hold, a checkout hold placed before SKU reservations — which is a child of {@code stock_item} and
 * never loaded or saved on its own; and a SKU reservation ({@code stock_item_id} null), the soft checkout hold,
 * which is its own aggregate and saved through {@code SkuReservationRepositoryAdapter}.
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

    /** Null for a SKU reservation: it is not on any stock item until it is allocated. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_item_id",
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_reservation_stock_item"))
    private StockItemJpaEntity stockItem;

    /**
     * The order holding the stock (a foreign key into {@code ordering} since V20260928004000, ADR-0007);
     * null for a production hold.
     */
    @Column(name = "order_id")
    private UUID orderId;

    /** The production order holding blanks (V20261017000400); exactly one of the two owners is set. */
    @Column(name = "production_order_id")
    private UUID productionOrderId;

    /** SCRUM-287 PR T: the transfer order the stock is allocated to. */
    @Column(name = "transfer_order_id")
    private UUID transferOrderId;

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

    /** Null once pinned: the order is no longer waiting for payment (SCRUM-465). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 32)
    private ReleaseReason releaseReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** The SKU held; for a hold on a stock item it is that item's ({@code tg_stock_reservation_sku}). */
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    /** The SKU reservation an allocation was made from (F-FUL step 02); null for every other hold. */
    @Column(name = "allocated_from_id", updatable = false)
    private UUID allocatedFrom;

    protected ReservationJpaEntity() {
    }

    /** A hold, with the SKU it holds and the SKU reservation it was allocated from. */
    public ReservationJpaEntity(UUID id, String sku, UUID orderId, UUID productionOrderId, UUID rootRequestId,
                                UUID requestId, int quantity, Instant reservedAt, Instant expiresAt,
                                ReservationStatus status, ReleaseReason releaseReason, Instant closedAt,
                                UUID allocatedFrom) {
        this(id, orderId, productionOrderId, rootRequestId, requestId, quantity, reservedAt, expiresAt, status,
                releaseReason, closedAt);
        this.sku = sku;
        this.allocatedFrom = allocatedFrom;
    }

    /** Copies a SKU reservation's changing state onto its managed row. */
    public void apply(Instant expiresAt, ReservationStatus status, ReleaseReason releaseReason, Instant closedAt) {
        this.expiresAt = expiresAt;
        this.status = status;
        this.releaseReason = releaseReason;
        this.closedAt = closedAt;
    }

    public ReservationJpaEntity(UUID id, UUID orderId, UUID rootRequestId, UUID requestId, int quantity,
                         Instant reservedAt, Instant expiresAt, ReservationStatus status,
                         ReleaseReason releaseReason, Instant closedAt) {
        this(id, orderId, null, rootRequestId, requestId, quantity, reservedAt, expiresAt, status, releaseReason,
                closedAt);
    }

    public ReservationJpaEntity(UUID id, UUID orderId, UUID productionOrderId, UUID rootRequestId, UUID requestId,
                         int quantity, Instant reservedAt, Instant expiresAt, ReservationStatus status,
                         ReleaseReason releaseReason, Instant closedAt) {
        this.id = id;
        this.orderId = orderId;
        this.productionOrderId = productionOrderId;
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
    public UUID getProductionOrderId() { return productionOrderId; }
    public UUID getTransferOrderId() { return transferOrderId; }

    /** SCRUM-287 PR T: the hold is a transfer order's. */
    public ReservationJpaEntity forTransfer(UUID transferOrderId) {
        this.transferOrderId = transferOrderId;
        return this;
    }
    public UUID getRootRequestId() { return rootRequestId; }
    public UUID getRequestId() { return requestId; }
    public int getQuantity() { return quantity; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public ReservationStatus getStatus() { return status; }
    public ReleaseReason getReleaseReason() { return releaseReason; }
    public Instant getClosedAt() { return closedAt; }
    public String getSku() { return sku; }
    /** The stock item the hold is on; null for a SKU reservation. Lazy: comparing with null loads nothing. */
    public StockItemJpaEntity getStockItemOrNull() { return stockItem; }
    public UUID getAllocatedFrom() { return allocatedFrom; }
}
