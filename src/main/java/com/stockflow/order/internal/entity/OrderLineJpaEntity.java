package com.stockflow.order.internal.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * JPA mapping of one order line.
 *
 * <p>{@code unit_price} is a stored column, not a lookup into the price list, because the price is
 * a fact about this order at the moment it was placed.</p>
 *
 * <p>The reservation ids live in a child table rather than a column, because one line can be drawn
 * from several lots and therefore hold several reservations. {@code @ElementCollection} is the
 * right tool here and not {@code @OneToMany}: a reservation id has no identity of its own on this
 * side — it is a value the line carries — and modelling it as an entity would invite someone to
 * load and mutate it independently. They point at rows in the {@code inventory} schema and
 * deliberately carry no foreign key; see {@code ReservationJpaEntity} for the reasoning.</p>
 */
@Entity
@Table(name = "order_line", schema = "ordering")
public class OrderLineJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_line_order"))
    private OrderJpaEntity order;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Only set for print-on-demand items; identifies the frozen design the customer approved. */
    @Column(name = "design_snapshot_id")
    private UUID designSnapshotId;

    @ElementCollection(fetch = jakarta.persistence.FetchType.EAGER)
    @CollectionTable(
            name = "order_line_reservation",
            schema = "ordering",
            joinColumns = @JoinColumn(name = "order_line_id",
                    foreignKey = @ForeignKey(name = "fk_line_reservation_line")))
    @Column(name = "reservation_id", nullable = false)
    // EAGER because there are at most a handful per line and every read of an order needs them to
    // know what to release. LAZY here would mean a second query per line on the cancellation path.
    //
    // A Set, not a List, and that choice has real consequences. Hibernate maps a List with no
    // @OrderColumn as a "bag", and a bag cannot be diffed: on every merge it is deleted and
    // re-inserted wholesale, so even markPaid - which touches no reservation - would rewrite these
    // rows and take locks on a table the cancellation path also uses. A PersistentSet compares
    // against its snapshot and emits no SQL when nothing changed. It is also what the migration
    // already models, with PRIMARY KEY (order_line_id, reservation_id).
    private Set<UUID> reservationIds = new LinkedHashSet<>();

    protected OrderLineJpaEntity() {
    }

    public OrderLineJpaEntity(UUID id, String sku, int quantity, BigDecimal unitPrice, String currency,
                       UUID designSnapshotId, Collection<UUID> reservationIds) {
        this.id = id;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.designSnapshotId = designSnapshotId;
        this.reservationIds = reservationIds == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(reservationIds);
    }

    public void attachTo(OrderJpaEntity parent) {
        this.order = parent;
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public Set<UUID> getReservationIds() { return reservationIds; }
}
