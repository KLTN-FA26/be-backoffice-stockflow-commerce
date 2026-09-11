package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.internal.domain.WaveStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a pick wave (table {@code fulfillment.wave}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "wave", schema = "fulfillment",
        uniqueConstraints = @UniqueConstraint(name = "uk_wave_code", columnNames = "code"))
public class WaveJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WaveStatus status;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected WaveJpaEntity() {
    }

    public WaveJpaEntity(UUID id, String code, WaveStatus status, Instant releasedAt) {
        super(id);
        this.code = code;
        this.status = status;
        this.releasedAt = releasedAt;
    }

    public String getCode() { return code; }
    public WaveStatus getStatus() { return status; }
    public Instant getReleasedAt() { return releasedAt; }
}
