package com.stockflow.catalog.internal.entity;

import com.stockflow.catalog.internal.domain.PromotionType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a promotion (table {@code catalog.promotion}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "promotion", schema = "catalog",
        uniqueConstraints = @UniqueConstraint(name = "uk_promotion_code", columnNames = "code"))
public class PromotionJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private PromotionType type;

    @Column(name = "value", nullable = false, precision = 18, scale = 2)
    private BigDecimal value;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected PromotionJpaEntity() {
    }

    public PromotionJpaEntity(UUID id, String code, String name, PromotionType type, BigDecimal value,
                              Instant startsAt, Instant endsAt, boolean active) {
        super(id);
        this.code = code;
        this.name = name;
        this.type = type;
        this.value = value;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = active;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public PromotionType getType() { return type; }
    public BigDecimal getValue() { return value; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isActive() { return active; }
}
