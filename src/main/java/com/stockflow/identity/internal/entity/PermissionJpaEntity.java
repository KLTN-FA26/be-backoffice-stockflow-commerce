package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a permission (table {@code identity.permission}). STARTER ENTITY.
 *
 * <p>{@code code} typically pairs a resource with an action (e.g. {@code orders:read}); the
 * {@code resource}/{@code action} columns break it out for querying.</p>
 */
@Entity
@Table(name = "permission", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_permission_code", columnNames = "code"))
public class PermissionJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, length = 128)
    private String code;

    @Column(name = "resource", length = 64)
    private String resource;

    @Column(name = "action", length = 64)
    private String action;

    @Column(name = "description", length = 500)
    private String description;

    protected PermissionJpaEntity() {
    }

    public PermissionJpaEntity(UUID id, String code, String resource, String action, String description) {
        super(id);
        this.code = code;
        this.resource = resource;
        this.action = action;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getResource() { return resource; }
    public String getAction() { return action; }
    public String getDescription() { return description; }
}
