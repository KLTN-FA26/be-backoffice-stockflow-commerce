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
 * <p>Inserting a row creates the SKU's inventory item (trigger {@code tg_variant_inventory_item},
 * docs 01 BR-07); the SKU may be renamed only while the variant is {@code DRAFT}
 * ({@code tg_variants_sku_frozen}), and the rename follows to the inventory item through
 * {@code ON UPDATE CASCADE}. {@code attributeSignature} is the normalised attribute combination
 * ({@code COLOR=GREY;SIZE=12OZ}) that {@code uk_variants_combination} keeps unique per product.</p>
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

    /** A customer's own SKU (kltn-docs 12 §4.5): the customer, the approved design and the variant it was drawn on. */
    @Column(name = "owner_customer_id", updatable = false)
    private UUID ownerCustomerId;

    @Column(name = "design_snapshot_id", updatable = false)
    private UUID designSnapshotId;

    @Column(name = "source_variant_id", updatable = false)
    private UUID sourceVariantId;

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

    /**
     * Makes this new variant a customer's own SKU, ACTIVE at once (kltn-docs 12 §4.5: the design's approval stands in for
     * a variant's activation). Never changed afterwards ({@code tg_variants_customer_sku}).
     */
    public void ownBy(UUID ownerCustomerId, UUID designSnapshotId, UUID sourceVariantId) {
        this.ownerCustomerId = ownerCustomerId;
        this.designSnapshotId = designSnapshotId;
        this.sourceVariantId = sourceVariantId;
        this.status = VariantStatus.ACTIVE;
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
    public UUID getOwnerCustomerId() { return ownerCustomerId; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public UUID getSourceVariantId() { return sourceVariantId; }
}
