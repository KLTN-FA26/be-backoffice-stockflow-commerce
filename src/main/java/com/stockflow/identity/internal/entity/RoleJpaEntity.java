package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a role (table {@code identity.app_role} — ROLE is a reserved word).
 *
 * <p>{@code system} marks the roles the code knows by name ({@code common.security.Role}): they are
 * never renamed or deleted. Roles administrators add (SCRUM-455) are not system roles.</p>
 *
 * <p>The JPA {@code @Version} of this row is also the version of its grants in the authorisation
 * cache (ADR-0008), so editing the name moves it too. That costs one cache miss, never a stale
 * answer.</p>
 */
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

    @Column(name = "is_system", nullable = false)
    private boolean system;

    protected RoleJpaEntity() {
    }

    public RoleJpaEntity(UUID id, String code, String name, String description) {
        this(id, code, name, description, false);
    }

    public RoleJpaEntity(UUID id, String code, String name, String description, boolean system) {
        super(id);
        this.code = code;
        this.name = name;
        this.description = description;
        this.system = system;
    }

    public void rename(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isSystem() { return system; }
}
