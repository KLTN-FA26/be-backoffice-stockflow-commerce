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
 * JPA mapping of one allowed value of an attribute (table {@code product.attribute_values}). Written
 * only through {@link AttributeJpaEntity}; its code never changes because products refer to the value
 * and reports group by it.
 */
@Entity
@Table(name = "attribute_values", schema = "product")
public class AttributeValueJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false, updatable = false)
    private AttributeJpaEntity attribute;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "value_number", precision = 18, scale = 4)
    private BigDecimal valueNumber;

    @Column(name = "hex_color", length = 7)
    private String hexColor;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected AttributeValueJpaEntity() {
    }

    public AttributeValueJpaEntity(UUID id, String code) {
        super(id);
        this.code = code;
    }

    void attachTo(AttributeJpaEntity attribute) {
        this.attribute = attribute;
    }

    public void apply(String label, BigDecimal valueNumber, String hexColor, int sortOrder, boolean active) {
        this.label = label;
        this.valueNumber = valueNumber;
        this.hexColor = hexColor;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public BigDecimal getValueNumber() { return valueNumber; }
    public String getHexColor() { return hexColor; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
