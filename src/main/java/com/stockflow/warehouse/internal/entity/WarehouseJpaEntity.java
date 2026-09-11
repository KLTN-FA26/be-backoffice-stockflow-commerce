package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a warehouse (table {@code warehouse.warehouse}). Not the domain model. STARTER ENTITY.
 */
@Entity
@Table(name = "warehouse", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_warehouse_code", columnNames = "code"))
public class WarehouseJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address_line", length = 255)
    private String addressLine;

    @Column(name = "city", length = 120)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WarehouseStatus status;

    protected WarehouseJpaEntity() {
    }

    public WarehouseJpaEntity(UUID id, String code, String name, String addressLine,
                              String city, WarehouseStatus status) {
        super(id);
        this.code = code;
        this.name = name;
        this.addressLine = addressLine;
        this.city = city;
        this.status = status;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getAddressLine() { return addressLine; }
    public String getCity() { return city; }
    public WarehouseStatus getStatus() { return status; }
}
