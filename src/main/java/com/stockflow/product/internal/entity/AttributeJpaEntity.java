package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.product.internal.domain.AttributeDataType;
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
 * JPA mapping of a product attribute (table {@code product.attributes}) with its allowed values.
 *
 * <p>{@code code} and {@code dataType} are fixed at creation: expressions and imports use the code,
 * and changing the type would invalidate every value already chosen. The values are cascaded and
 * orphan-removed because the aggregate owns them; {@code fk_attribute_values_attribute} is RESTRICT,
 * so removing the attribute must remove its value rows first, which the cascade does.</p>
 */
@Entity
@Table(name = "attributes", schema = "product")
public class AttributeJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 16, updatable = false)
    private AttributeDataType dataType;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "is_filterable", nullable = false)
    private boolean filterable;

    @Column(name = "is_searchable", nullable = false)
    private boolean searchable;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @OneToMany(mappedBy = "attribute", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AttributeValueJpaEntity> values = new ArrayList<>();

    protected AttributeJpaEntity() {
    }

    public AttributeJpaEntity(UUID id, String code, AttributeDataType dataType) {
        super(id);
        this.code = code;
        this.dataType = dataType;
    }

    public void apply(String name, String unit, boolean filterable, boolean searchable, int sortOrder,
                      boolean active) {
        this.name = name;
        this.unit = unit;
        this.filterable = filterable;
        this.searchable = searchable;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    /** Appends a value and points it back here; it is inserted with the attribute on the next flush. */
    public void addValue(AttributeValueJpaEntity value) {
        value.attachTo(this);
        values.add(value);
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public AttributeDataType getDataType() { return dataType; }
    public String getUnit() { return unit; }
    public boolean isFilterable() { return filterable; }
    public boolean isSearchable() { return searchable; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public List<AttributeValueJpaEntity> getValues() { return values; }
}
