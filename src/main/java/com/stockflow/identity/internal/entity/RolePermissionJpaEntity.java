package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** Join row granting a permission to a role (table {@code identity.role_permission}). STARTER ENTITY. */
@Entity
@Table(name = "role_permission", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_permission", columnNames = {"role_id", "permission_id"}))
public class RolePermissionJpaEntity extends BaseEntity {

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "permission_id", nullable = false)
    private UUID permissionId;

    protected RolePermissionJpaEntity() {
    }

    public RolePermissionJpaEntity(UUID id, UUID roleId, UUID permissionId) {
        super(id);
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public UUID getRoleId() { return roleId; }
    public UUID getPermissionId() { return permissionId; }
}
