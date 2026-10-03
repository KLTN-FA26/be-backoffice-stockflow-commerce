package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.RolePermissionJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link RolePermissionJpaEntity}. */
public interface RolePermissionJpaRepository extends BaseJpaRepository<RolePermissionJpaEntity> {

    List<RolePermissionJpaEntity> findByRoleId(UUID roleId);

    /** Whether any role other than {@code roleId} holds the permission — the lockout check. */
    boolean existsByPermissionIdAndRoleIdNot(UUID permissionId, UUID roleId);

    /**
     * Takes a transaction-scoped advisory lock, released at commit or rollback. Serialises every
     * permission-matrix edit, so a check that reads other roles' grants sees them committed.
     *
     * <p>Wrapped in a sub-select because {@code pg_advisory_xact_lock} returns {@code void}, which
     * has no JDBC type Hibernate can map.</p>
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS locked", nativeQuery = true)
    Integer lockMatrixEdits(@Param("key") long key);
}
