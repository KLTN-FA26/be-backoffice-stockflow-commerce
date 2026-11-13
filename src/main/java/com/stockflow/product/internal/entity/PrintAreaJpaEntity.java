package com.stockflow.product.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * JPA mapping of a print area (table {@code product.print_areas}), computed from its template's model and replaced
 * whole whenever the template is saved. The database refuses a code the studio does not draw and an area without its
 * computed shape ({@code ck_print_areas_code}, {@code ck_print_areas_shape}).
 */
@Entity
@Table(name = "print_areas", schema = "product")
public class PrintAreaJpaEntity extends BaseEntity {

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 255, updatable = false)
    private String name;

    @Column(name = "x_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal xMm;

    @Column(name = "y_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal yMm;

    @Column(name = "width_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal widthMm;

    @Column(name = "height_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal heightMm;

    @Column(name = "safe_margin_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal safeMarginMm;

    @Column(name = "bleed_mm", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal bleedMm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shape_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> shape;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "uv_mapping_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> uvMapping;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected PrintAreaJpaEntity() {
    }

    public PrintAreaJpaEntity(UUID id, UUID templateId, String code, String name, BigDecimal xMm, BigDecimal yMm,
                              BigDecimal widthMm, BigDecimal heightMm, BigDecimal safeMarginMm, BigDecimal bleedMm,
                              Map<String, Object> shape, Map<String, Object> uvMapping, int sortOrder) {
        super(id);
        this.templateId = templateId;
        this.code = code;
        this.name = name;
        this.xMm = xMm;
        this.yMm = yMm;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
        this.safeMarginMm = safeMarginMm;
        this.bleedMm = bleedMm;
        this.shape = shape;
        this.uvMapping = uvMapping;
        this.sortOrder = sortOrder;
    }

    public UUID getTemplateId() { return templateId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public BigDecimal getXMm() { return xMm; }
    public BigDecimal getYMm() { return yMm; }
    public BigDecimal getWidthMm() { return widthMm; }
    public BigDecimal getHeightMm() { return heightMm; }
    public BigDecimal getSafeMarginMm() { return safeMarginMm; }
    public BigDecimal getBleedMm() { return bleedMm; }
    public Map<String, Object> getShape() { return shape; }
    public Map<String, Object> getUvMapping() { return uvMapping; }
    public int getSortOrder() { return sortOrder; }
}
