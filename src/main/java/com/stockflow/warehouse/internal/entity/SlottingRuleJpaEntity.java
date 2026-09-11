package com.stockflow.warehouse.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a slotting rule (table {@code warehouse.slotting_rule}). Not the domain model.
 *
 * <p>STARTER ENTITY. Governs where incoming stock is placed (fast movers to the golden zone, etc.).
 * {@code criteria} is JSON held as text for now. {@code warehouseId} is a same-schema reference.</p>
 */
@Entity
@Table(name = "slotting_rule", schema = "warehouse")
public class SlottingRuleJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "criteria", length = 2000)
    private String criteria;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected SlottingRuleJpaEntity() {
    }

    public SlottingRuleJpaEntity(UUID id, UUID warehouseId, String name, String criteria,
                                 int priority, boolean active) {
        super(id);
        this.warehouseId = warehouseId;
        this.name = name;
        this.criteria = criteria;
        this.priority = priority;
        this.active = active;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public String getName() { return name; }
    public String getCriteria() { return criteria; }
    public int getPriority() { return priority; }
    public boolean isActive() { return active; }
}
