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
 * <p>STARTER ENTITY. The {@code code} is the SKU string used across modules (inventory rows,
 * order lines). {@code lotTracked} decides whether inventory keeps lot/expiry for it. Same-schema
 * reference to {@code product.variant}.</p>
 */
@Entity
@Table(name = "sku", schema = "product",
        uniqueConstraints = @UniqueConstraint(name = "uk_sku_code", columnNames = "code"))
public class SkuJpaEntity extends BaseEntity {

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
