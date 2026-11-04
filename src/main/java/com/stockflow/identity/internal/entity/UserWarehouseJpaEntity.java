package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** One warehouse a staff member is assigned to (table {@code identity.user_warehouse}, SCRUM-457). */
@Entity
@Table(name = "user_warehouse", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_warehouse", columnNames = {"user_id", "warehouse_id"}))
public class UserWarehouseJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    protected UserWarehouseJpaEntity() {
    }

    public UserWarehouseJpaEntity(UUID id, UUID userId, UUID warehouseId) {
        super(id);
        this.userId = userId;
        this.warehouseId = warehouseId;
    }

    public UUID getUserId() { return userId; }
    public UUID getWarehouseId() { return warehouseId; }
}
