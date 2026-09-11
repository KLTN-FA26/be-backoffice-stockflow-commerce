package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** JPA mapping of a role (table {@code identity.app_role} — ROLE is a reserved word). STARTER ENTITY. */
@Entity
@Table(name = "app_role", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_role_code", columnNames = "code"))
public class RoleJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    protected RoleJpaEntity() {
    }

    public RoleJpaEntity(UUID id, String code, String name, String description) {
        super(id);
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
