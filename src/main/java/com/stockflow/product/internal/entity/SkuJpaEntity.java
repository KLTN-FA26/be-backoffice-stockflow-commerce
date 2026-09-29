package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a SKU (table {@code product.sku}). Not the domain model.
 *
 * <p>The {@code code} is the SKU string used across modules (inventory rows,
 * order lines). Lot/serial tracking and expiry are distinct SKU policies. Same-schema
 * reference to {@code product.variant}.</p>
 */
@Entity
@Table(name = "sku", schema = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_sku_code", columnNames = "code"))
public class SkuJpaEntity extends BaseEntity {

    @Column(name = "reorder_point")
    private Integer reorderPoint;
    @Column(name = "safety_stock")
    private Integer safetyStock;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "removal_strategy", nullable = false)
    private com.stockflow.inventory.api.RemovalStrategy removalStrategy = com.stockflow.inventory.api.RemovalStrategy.FEFO;
    @Column(name = "serial_tracked", nullable = false)
    private boolean serialTracked;
    @Column(name = "expiry_tracked", nullable = false)
    private boolean expiryTracked;
    @Column(name = "max_shelf_life_days")
    private Integer maxShelfLifeDays;

    public com.stockflow.inventory.api.InventoryPolicy inventoryControl() {
        var mode = serialTracked ? com.stockflow.inventory.api.TrackingMode.SERIAL
                : lotTracked ? com.stockflow.inventory.api.TrackingMode.LOT : com.stockflow.inventory.api.TrackingMode.NONE;
        return new com.stockflow.inventory.api.InventoryPolicy(reorderPoint, safetyStock, removalStrategy,
                mode, expiryTracked, maxShelfLifeDays);
    }
    public void applyInventoryControl(com.stockflow.inventory.api.InventoryPolicy policy) {
        reorderPoint = policy.reorderPoint(); safetyStock = policy.safetyStock();
        removalStrategy = policy.removalStrategy();
        lotTracked = policy.trackingMode() == com.stockflow.inventory.api.TrackingMode.LOT;
        serialTracked = policy.trackingMode() == com.stockflow.inventory.api.TrackingMode.SERIAL;
        expiryTracked = policy.expiryTracked(); maxShelfLifeDays = policy.maxShelfLifeDays();
    }

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @Column(name = "lot_tracked", nullable = false)
    private boolean lotTracked;

    @Column(name = "unit_of_measure", nullable = false, length = 16)
    private String unitOfMeasure;

    protected SkuJpaEntity() {
    }

    public SkuJpaEntity(UUID id, UUID variantId, String code, String barcode,
                        boolean lotTracked, String unitOfMeasure) {
        super(id);
        this.variantId = variantId;
        this.code = code;
        this.barcode = barcode;
        this.lotTracked = lotTracked;
        this.unitOfMeasure = unitOfMeasure;
    }

    public UUID getVariantId() { return variantId; }
    public String getCode() { return code; }
    public String getBarcode() { return barcode; }
    public boolean isLotTracked() { return lotTracked; }
    public String getUnitOfMeasure() { return unitOfMeasure; }
}
