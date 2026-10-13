package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.AttributeRole;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of an attribute attached to a product (table {@code product.product_attributes}).
 *
 * <p>Product, attribute and role are fixed once the row exists: the role is immutable by trigger
 * ({@code check_product_attribute}), and moving a row to another product or attribute would orphan
 * the options variants point at. Options and descriptive values are two separate bags, which is why
 * they can never be fetch-joined in one query (MultipleBagFetchException).</p>
 */
@Entity
@Table(name = "product_attributes", schema = "product")
public class ProductAttributeJpaEntity extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "attribute_id", nullable = false, updatable = false)
    private UUID attributeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private AttributeRole role;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @OneToMany(mappedBy = "productAttribute", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductAttributeOptionJpaEntity> options = new ArrayList<>();

    @OneToMany(mappedBy = "productAttribute", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductDescriptiveValueJpaEntity> descriptiveValues = new ArrayList<>();

    protected ProductAttributeJpaEntity() {
    }

    public ProductAttributeJpaEntity(UUID id, UUID productId, UUID attributeId, AttributeRole role, int sortOrder) {
        super(id);
        this.productId = productId;
        this.attributeId = attributeId;
        this.role = role;
        this.sortOrder = sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** Appends an option and points it back here; it is inserted on the next flush. */
    public void addOption(ProductAttributeOptionJpaEntity option) {
        option.attachTo(this);
        options.add(option);
    }

    /** Appends a descriptive value and points it back here; it is inserted on the next flush. */
    public void addDescriptiveValue(ProductDescriptiveValueJpaEntity value) {
        value.attachTo(this);
        descriptiveValues.add(value);
    }

    public UUID getProductId() { return productId; }
    public UUID getAttributeId() { return attributeId; }
    public AttributeRole getRole() { return role; }
    public int getSortOrder() { return sortOrder; }
    public List<ProductAttributeOptionJpaEntity> getOptions() { return options; }
    public List<ProductDescriptiveValueJpaEntity> getDescriptiveValues() { return descriptiveValues; }
}
