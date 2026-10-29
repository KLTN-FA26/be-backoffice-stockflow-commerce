package com.stockflow.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code inventory.stock_damage_supplier_shipment}: units of a return-to-supplier decision handed to the supplier under a
 * delivery note, or refused by it, a child of the damage report. Append-only in the table, so insert-only here.
 */
@Entity
@Table(name = "stock_damage_supplier_shipment", schema = "inventory")
public class StockDamageSupplierShipmentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "damage_report_id", nullable = false, updatable = false)
    private StockDamageReportJpaEntity report;

    @Column(name = "disposition_id", nullable = false, updatable = false)
    private UUID dispositionId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "delivery_note", length = 64, updatable = false)
    private String deliveryNote;

    /** SHIPPED (handed over, off the books) or REFUSED (the supplier would not take them). */
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private String outcome;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "shipped_by", nullable = false, updatable = false)
    private UUID shippedBy;

    @Column(name = "shipped_at", nullable = false, updatable = false)
    private Instant shippedAt;

    protected StockDamageSupplierShipmentJpaEntity() {
    }

    public StockDamageSupplierShipmentJpaEntity(UUID id, UUID dispositionId, UUID requestId, int quantity,
                                                String deliveryNote, String note, UUID shippedBy, Instant shippedAt,
                                                boolean refused) {
        this.outcome = refused ? "REFUSED" : "SHIPPED";
        this.id = id;
        this.dispositionId = dispositionId;
        this.requestId = requestId;
        this.quantity = quantity;
        this.deliveryNote = deliveryNote;
        this.note = note;
        this.shippedBy = shippedBy;
        this.shippedAt = shippedAt;
    }

    void attachTo(StockDamageReportJpaEntity owner) {
        this.report = owner;
    }

    public UUID getId() { return id; }
    public UUID getDispositionId() { return dispositionId; }
    public UUID getRequestId() { return requestId; }
    public int getQuantity() { return quantity; }
    public String getDeliveryNote() { return deliveryNote; }
    public String getNote() { return note; }
    public UUID getShippedBy() { return shippedBy; }
    public Instant getShippedAt() { return shippedAt; }
    public boolean isRefused() { return "REFUSED".equals(outcome); }
}
