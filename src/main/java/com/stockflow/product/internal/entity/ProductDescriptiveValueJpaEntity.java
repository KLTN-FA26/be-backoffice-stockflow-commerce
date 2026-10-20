package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of one stored value of a descriptive attribute (table
 * {@code product.product_descriptive_values}). Its value fields are updatable on purpose: a SELECT
 * attribute or a scalar that changes must change this row, because {@code check_descriptive_value}
 * refuses a second row for anything but MULTISELECT.
 */
@Entity
@Table(name = "product_descriptive_values", schema = "product")
public class ProductDescriptiveValueJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_attribute_id", nullable = false, updatable = false)
    private ProductAttributeJpaEntity productAttribute;

    @Column(name = "attribute_value_id")
    private UUID attributeValueId;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "value_number", precision = 18, scale = 4)
    private BigDecimal valueNumber;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    protected ProductDescriptiveValueJpaEntity() {
    }

    public ProductDescriptiveValueJpaEntity(UUID id) {
        super(id);
    }

    void attachTo(ProductAttributeJpaEntity productAttribute) {
        this.productAttribute = productAttribute;
    }

    public void apply(UUID attributeValueId, String valueText, BigDecimal valueNumber, Boolean valueBoolean) {
        this.attributeValueId = attributeValueId;
        this.valueText = valueText;
        this.valueNumber = valueNumber;
        this.valueBoolean = valueBoolean;
    }

    public UUID getAttributeValueId() { return attributeValueId; }
    public String getValueText() { return valueText; }
    public BigDecimal getValueNumber() { return valueNumber; }
    public Boolean getValueBoolean() { return valueBoolean; }
}
