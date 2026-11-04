package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a supplier contact (table {@code procurement.supplier_contacts}). The supplier API
 * shows and edits one of them, the primary ({@code uk_supplier_contacts_primary}): its email is
 * where an EMAIL-channel purchase order is sent.
 */
@Entity
@Table(name = "supplier_contacts", schema = "procurement")
public class SupplierContactJpaEntity extends BaseEntity {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "position", length = 100)
    private String position;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected SupplierContactJpaEntity() {
    }

    public SupplierContactJpaEntity(UUID id, UUID supplierId) {
        super(id);
        this.supplierId = supplierId;
        this.primary = true;
    }

    public void update(String fullName, String email, String phone) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
    }

    public UUID getSupplierId() { return supplierId; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public boolean isPrimary() { return primary; }
}
