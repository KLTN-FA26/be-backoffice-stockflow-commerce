package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.CustomerSkuStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a customer's own SKU (table {@code product.customer_skus}, V20270105000100; owner decision Q4 of
 * 2026-11-26): issued when the customer's design is approved, drawn on a catalogue variant of the product, and the only
 * goods quoted, ordered and stocked.
 *
 * <p>Who and what the SKU is never changes - its product, its variant, its customer, its design snapshot and its code
 * are not updatable here and {@code tg_customer_skus_immutable} refuses it in the database - because a quote, an order,
 * a stocked carton and a chat card name it. Only the name and the status move. The code is never also a catalogue
 * variant's ({@code tg_customer_skus_code_free}).</p>
 *
 * <p>Unlike a catalogue variant once did, inserting one makes no inventory item: the item is made when Sales converts a
 * quote naming the SKU into an order (owner decision Q5, {@code InventoryService.openItems}).</p>
 */
@Entity
@Table(name = "customer_skus", schema = "product")
public class CustomerSkuJpaEntity extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    /** The catalogue variant the design was drawn on: its carton is the SKU's (Q1). */
    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "design_snapshot_id", nullable = false, updatable = false)
    private UUID designSnapshotId;

    @Column(name = "sku", nullable = false, updatable = false, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CustomerSkuStatus status;

    @Column(name = "obsoleted_at")
    private Instant obsoletedAt;

    protected CustomerSkuJpaEntity() {
    }

    /** A SKU as the design approval issues it: ACTIVE at once (kltn-docs 12 §4.5). */
    public CustomerSkuJpaEntity(UUID id, UUID productId, UUID variantId, UUID customerId, UUID designSnapshotId,
                                String sku, String name) {
        super(id);
        this.productId = productId;
        this.variantId = variantId;
        this.customerId = customerId;
        this.designSnapshotId = designSnapshotId;
        this.sku = sku;
        this.name = name;
        this.status = CustomerSkuStatus.ACTIVE;
    }

    /** Moves to {@code target}; whether the move is allowed is {@link CustomerSkuStatus#canTransitionTo}'s to say. */
    public void moveTo(CustomerSkuStatus target, Instant now) {
        this.status = target;
        this.obsoletedAt = target == CustomerSkuStatus.OBSOLETE ? now : null;
    }

    public UUID getProductId() { return productId; }
    public UUID getVariantId() { return variantId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public CustomerSkuStatus getStatus() { return status; }
    public Instant getObsoletedAt() { return obsoletedAt; }
}
