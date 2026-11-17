package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.design.internal.domain.TextStyle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code design.design_project_text}: a text and its type style in a design project's library (FE request 2026-11-13
 * E-1, beyond kltn-docs 12 BR-18). Never changed: the customer removes it and saves another. Placing it on a design
 * is the studio's copy into the layout, so removing it here leaves every design as it is.
 */
@Entity
@Table(name = "design_project_text", schema = "design")
public class DesignProjectTextJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "text", nullable = false, updatable = false, length = 500)
    private String text;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style", nullable = false, updatable = false, columnDefinition = "jsonb")
    private TextStyle style;

    protected DesignProjectTextJpaEntity() {
    }

    public DesignProjectTextJpaEntity(UUID projectId, String text, TextStyle style) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.text = text;
        this.style = style;
    }

    public UUID getProjectId() { return projectId; }
    public String getText() { return text; }
    public TextStyle getStyle() { return style; }
}
