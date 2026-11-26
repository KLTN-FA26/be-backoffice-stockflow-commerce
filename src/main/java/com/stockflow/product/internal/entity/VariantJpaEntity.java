package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.VariantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a variant (table {@code product.variants}): one sellable form of a product,
 * identified everywhere downstream by its {@code sku}.
 *
 * <p>A variant is the catalogue's alone since V20270105000100: a customer's own SKU is a row of
 * {@code product.customer_skus} drawn on one ({@code CustomerSkuJpaEntity}), and no variant has an inventory item -
 * it carries the carton ({@code inventory.uom_conversions.variant_id}) and the standard price that its customers' SKUs
 * are packed in and priced from. The SKU may be renamed only while the variant is {@code DRAFT}
 * ({@code tg_variants_sku_frozen}), and never to a customer's SKU's code ({@code tg_variants_code_free}).
 * {@code attributeSignature} is the normalised attribute combination ({@code COLOR=GREY;SIZE=12OZ}) that
 * {@code uk_variants_combination} keeps unique per product.</p>
 */
@Entity
@Table(name = "variants", schema = "product")
public class VariantJpaEntity extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VariantStatus status;

    @Column(name = "is_default", nullable = false)
    private boolean defaultVariant;

    @Column(name = "attribute_signature", nullable = false, length = 512)
    private String attributeSignature;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "obsoleted_at")
    private Instant obsoletedAt;

    /**
     * Whether a carton of this variant may be turned (owner decision Q9, V20270104000200): every SKU of the variant is
     * packed in its carton, so inventory and the load planner ask the variant, through {@code inventory :: spi}.
     */
    @Column(name = "rotatable", nullable = false)
    private boolean rotatable = true;

    protected VariantJpaEntity() {
    }

    public VariantJpaEntity(UUID id, UUID productId, String sku, String name, String attributeSignature,
                            int position, boolean defaultVariant) {
        super(id);
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.attributeSignature = attributeSignature;
        this.position = position;
        this.defaultVariant = defaultVariant;
        this.status = VariantStatus.DRAFT;
    }

    public void setDetails(String sku, String name, String attributeSignature, int position) {
        this.sku = sku;
        this.name = name;
        this.attributeSignature = attributeSignature;
        this.position = position;
    }

    public void setRotatable(boolean rotatable) {
        this.rotatable = rotatable;
    }

    public void moveTo(VariantStatus target, Instant now) {
        this.status = target;
        this.obsoletedAt = target == VariantStatus.OBSOLETE ? now : null;
    }

    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public VariantStatus getStatus() { return status; }
    public boolean isDefaultVariant() { return defaultVariant; }
    public String getAttributeSignature() { return attributeSignature; }
    public int getPosition() { return position; }
    public Instant getObsoletedAt() { return obsoletedAt; }
    public boolean isRotatable() { return rotatable; }
}
