package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.RoleJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link RoleJpaEntity}. */
public interface RoleJpaRepository extends BaseJpaRepository<RoleJpaEntity> {

    Optional<RoleJpaEntity> findByCode(String code);

    boolean existsByCode(String code);

    List<RoleJpaEntity> findByCodeIn(java.util.Collection<String> codes);

    /** Holders per role, for the role list: {@code [roleId, count]}. */
    @Query("select ur.roleId, count(ur) from UserRoleJpaEntity ur group by ur.roleId")
    List<Object[]> countHoldersPerRole();

    List<RoleJpaEntity> findAllByOrderByCodeAsc();

    /** Every role's current version, for the authorisation cache's version pointer (ADR-0008). */
    @Query("select r.code as code, r.version as version from RoleJpaEntity r")
    List<RoleVersionRow> findAllVersions();

    /**
     * One role's version and every permission it grants, in a <b>single statement</b>.
     *
     * <p>One statement is the point. Under READ COMMITTED two separate reads may see two different
     * commits, and reading the grants before the version could pair an old set of permissions with
     * a new version — the cache would then store stale grants under the key readers trust. A single
     * statement reads one snapshot, so the version and the grants always belong together.</p>
     *
     * <p>A role with no grants returns one row with null resource and action; an unknown code
     * returns no rows.</p>
     */
    @Query(value = """
            SELECT r.version AS version, p.resource AS resource, p.action AS action, r.data_scope AS dataScope
            FROM identity.app_role r
            LEFT JOIN identity.role_permission rp ON rp.role_id = r.id
            LEFT JOIN identity.permission p ON p.id = rp.permission_id
            WHERE r.code = :code
            """, nativeQuery = true)
    List<RoleGrantRow> findGrantsByCode(@Param("code") String code);

    /**
     * Compare-and-set on the role's version: moves it one step forward only if it still equals what
     * the editor loaded.
     *
     * <p>Editing a role's grants only writes {@code role_permission} rows, so JPA would never bump
     * the role's own {@code @Version}, and the cache key (role + version) would not change either.
     * This update is what makes a grant change a version change. The row lock it takes also
     * serialises two concurrent editors: the second waits, then matches zero rows.</p>
     *
     * @return 1 when the version moved, 0 when someone else moved it first
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE identity.app_role
            SET version = version + 1, last_modified_at = :at, last_modified_by = :by
            WHERE id = :id AND version = :expected
            """, nativeQuery = true)
    int advanceVersion(@Param("id") UUID id, @Param("expected") long expected,
                       @Param("at") Instant at, @Param("by") String by);
}
