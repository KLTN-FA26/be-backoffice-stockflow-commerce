package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/** Join row assigning a role to a user (table {@code identity.user_role}). STARTER ENTITY. */
@Entity
@Table(name = "user_role", schema = "identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_role", columnNames = {"user_id", "role_id"}))
public class UserRoleJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    protected UserRoleJpaEntity() {
    }

    public UserRoleJpaEntity(UUID id, UUID userId, UUID roleId) {
        super(id);
        this.userId = userId;
        this.roleId = roleId;
    }

    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }
}
