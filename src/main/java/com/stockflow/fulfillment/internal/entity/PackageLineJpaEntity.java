package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * What one parcel holds of one order line, the running total of its scans (table {@code fulfillment.package_line}).
 * The scans ({@link PackageScanJpaEntity}) carry the lot and serial; this row is what load planning, the label and the
 * over-pack trigger read.
 */
@Entity
@Table(name = "package_line", schema = "fulfillment")
public class PackageLineJpaEntity extends BaseEntity {

    @Column(name = "package_id", nullable = false, updatable = false) private UUID packageId;
    @Column(name = "order_line_id", nullable = false, updatable = false) private UUID orderLineId;
    @Column(name = "quantity", nullable = false) private int quantity;

    protected PackageLineJpaEntity() {
    }

    public PackageLineJpaEntity(UUID id, UUID packageId, UUID orderLineId, int quantity) {
        super(id);
        this.packageId = packageId;
        this.orderLineId = orderLineId;
        this.quantity = quantity;
    }

    public void setQuantity(int quantity) { this.quantity = quantity; }
    public UUID getPackageId() { return packageId; }
    public UUID getOrderLineId() { return orderLineId; }
    public int getQuantity() { return quantity; }
}
