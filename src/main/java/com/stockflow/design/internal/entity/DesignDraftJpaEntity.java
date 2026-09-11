package com.stockflow.design.internal.entity;

import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA mapping of a design draft (table {@code design.design_draft}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "design_draft", schema = "design")
public class DesignDraftJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "spec", length = 4000)
    private String spec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DesignStatus status;

    protected DesignDraftJpaEntity() {
    }

    public DesignDraftJpaEntity(UUID id, UUID customerId, UUID productId, String name,
                                String spec, DesignStatus status) {
        super(id);
        this.customerId = customerId;
        this.productId = productId;
        this.name = name;
        this.spec = spec;
        this.status = status;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getProductId() { return productId; }
    public String getName() { return name; }
    public String getSpec() { return spec; }
    public DesignStatus getStatus() { return status; }
}
