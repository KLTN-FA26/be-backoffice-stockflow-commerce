package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** JPA mapping of a supplier (table {@code procurement.supplier}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "supplier", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_code", columnNames = "code"))
public class SupplierJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "tax_code", length = 32)
    private String taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SupplierStatus status;

    protected SupplierJpaEntity() {
    }

    public SupplierJpaEntity(UUID id, String code, String name, String email, String phone,
                             String taxCode, SupplierStatus status) {
        super(id);
        this.code = code;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.taxCode = taxCode;
        this.status = status;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getTaxCode() { return taxCode; }
    public SupplierStatus getStatus() { return status; }
}
