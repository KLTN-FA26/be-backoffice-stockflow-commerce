package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a purchase-order line (table {@code procurement.po_line}). Not the domain model.
 *
 * <p>{@code sku} is a cross-module reference to product.sku by code (no FK). {@code @ManyToOne}
 * back to the parent PO — cascade-managed the same way {@code ProductImageJpaEntity} is, since a
 * line has no independent lifecycle of its own.</p>
 */
@Entity
@Table(name = "po_line", schema = "procurement")
public class POLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_po_line_order"))
    private PurchaseOrderJpaEntity purchaseOrder;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    protected POLineJpaEntity() {
    }

    public POLineJpaEntity(UUID id, String sku, String description,
                           int quantityOrdered, int quantityReceived, BigDecimal unitPrice) {
        super(id);
        this.sku = sku;
        this.description = description;
        this.quantityOrdered = quantityOrdered;
        this.quantityReceived = quantityReceived;
        this.unitPrice = unitPrice;
    }

    public void attachTo(PurchaseOrderJpaEntity parent) {
        this.purchaseOrder = parent;
    }

    public UUID getPurchaseOrderId() { return purchaseOrder == null ? null : purchaseOrder.getId(); }
    public String getSku() { return sku; }
    public String getDescription() { return description; }
    public int getQuantityOrdered() { return quantityOrdered; }
    public int getQuantityReceived() { return quantityReceived; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
