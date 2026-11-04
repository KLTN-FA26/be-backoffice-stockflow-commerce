package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of one price period of a supplier item (table
 * {@code procurement.supplier_item_prices}). Not the domain model.
 *
 * <p>The periods of one item never overlap ({@code ex_supplier_item_prices_overlap}) and at most one
 * is open ({@code effective_to} null). The adapter closes the open one and flushes before it inserts
 * the next: Hibernate runs inserts ahead of updates, which would otherwise put two open periods in
 * the table for an instant and trip that constraint.</p>
 */
@Entity
@Table(name = "supplier_item_prices", schema = "procurement")
public class SupplierItemPriceJpaEntity extends BaseEntity {

    @Column(name = "supplier_item_id", nullable = false, updatable = false)
    private UUID supplierItemId;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    protected SupplierItemPriceJpaEntity() {
    }

    public SupplierItemPriceJpaEntity(UUID id, UUID supplierItemId, BigDecimal unitPrice, String currency,
                                      Instant effectiveFrom) {
        super(id);
        this.supplierItemId = supplierItemId;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.effectiveFrom = effectiveFrom;
    }

    /** Rewrites the period's price; the currency too, since a reprice may move a row into its supplier's. */
    public void reprice(BigDecimal unitPrice, String currency) {
        this.unitPrice = unitPrice;
        this.currency = currency;
    }

    public void closeAt(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public UUID getSupplierItemId() { return supplierItemId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
}
