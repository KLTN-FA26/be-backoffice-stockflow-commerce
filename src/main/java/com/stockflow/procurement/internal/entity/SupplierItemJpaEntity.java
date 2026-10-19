package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of what a supplier sells (table {@code procurement.supplier_items}). Not the domain
 * model.
 *
 * <p>{@code supplierId} and {@code inventoryItemId} are fixed at creation: moving a row to another
 * supplier or SKU is a different offer, and {@code uk_supplier_items_pair} is what keeps one row
 * per pair. {@code current_price} is the price the open period of
 * {@link SupplierItemPriceJpaEntity} carries; the adapter writes both in one transaction.</p>
 */
@Entity
@Table(name = "supplier_items", schema = "procurement")
public class SupplierItemJpaEntity extends BaseEntity {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "supplier_sku_code", length = 50)
    private String supplierSkuCode;

    @Column(name = "current_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "moq", nullable = false)
    private int moq;

    @Column(name = "pack_size", nullable = false)
    private int packSize;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "is_preferred", nullable = false)
    private boolean preferred;

    protected SupplierItemJpaEntity() {
    }

    public SupplierItemJpaEntity(UUID id, UUID supplierId, UUID inventoryItemId) {
        super(id);
        this.supplierId = supplierId;
        this.inventoryItemId = inventoryItemId;
    }

    public void update(String supplierSkuCode, BigDecimal currentPrice, String currency, int moq, int packSize,
                       Integer leadTimeDays, boolean preferred) {
        this.supplierSkuCode = supplierSkuCode;
        this.currentPrice = currentPrice;
        this.currency = currency;
        this.moq = moq;
        this.packSize = packSize;
        this.leadTimeDays = leadTimeDays;
        this.preferred = preferred;
    }

    public UUID getSupplierId() { return supplierId; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public String getSupplierSkuCode() { return supplierSkuCode; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public String getCurrency() { return currency; }
    public int getMoq() { return moq; }
    public int getPackSize() { return packSize; }
    public Integer getLeadTimeDays() { return leadTimeDays; }
    public boolean isPreferred() { return preferred; }
}
