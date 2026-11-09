package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** {@code customer.customer_legal_profile} (4B-4): one row per customer, written whole. */
@Entity
@Table(name = "customer_legal_profile", schema = "customer")
public class CustomerLegalProfileJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "registered_address", nullable = false, length = 500)
    private String registeredAddress;

    @Column(name = "representative_name", nullable = false, length = 200)
    private String representativeName;

    @Column(name = "representative_title", nullable = false, length = 100)
    private String representativeTitle;

    protected CustomerLegalProfileJpaEntity() {
    }

    public CustomerLegalProfileJpaEntity(UUID id, UUID customerId) {
        super(id);
        this.customerId = customerId;
    }

    public void apply(String registeredAddress, String representativeName, String representativeTitle) {
        this.registeredAddress = registeredAddress;
        this.representativeName = representativeName;
        this.representativeTitle = representativeTitle;
    }

    public UUID getCustomerId() { return customerId; }
    public String getRegisteredAddress() { return registeredAddress; }
    public String getRepresentativeName() { return representativeName; }
    public String getRepresentativeTitle() { return representativeTitle; }
}
