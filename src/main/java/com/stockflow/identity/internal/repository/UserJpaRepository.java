package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserJpaEntity}. */
public interface UserJpaRepository extends BaseJpaRepository<UserJpaEntity> {

    Optional<UserJpaEntity> findByUsernameIgnoreCase(String username);
    Optional<UserJpaEntity> findByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserJpaEntity u where u.id = :id")
    Optional<UserJpaEntity> lockById(@Param("id") UUID id);

    /**
     * The user-management list (SCRUM-454). Every filter is optional.
     *
     * <p>{@code excludedRoleId} keeps customer accounts out of the staff list: they are managed from
     * the customer screens, and a shop's customers would bury its dozen staff accounts.</p>
     *
     * @param pattern lower-case {@code %text%}, matched against username, e-mail and full name
     */
    @Query("""
            select u from UserJpaEntity u
            where (:pattern is null
                   or lower(u.username) like :pattern
                   or lower(u.email) like :pattern
                   or lower(coalesce(u.fullName, '')) like :pattern)
              and (:status is null or u.status = :status)
              and (:roleId is null or exists (
                    select 1 from UserRoleJpaEntity ur where ur.userId = u.id and ur.roleId = :roleId))
              and (:excludedRoleId is null or not exists (
                    select 1 from UserRoleJpaEntity ux where ux.userId = u.id and ux.roleId = :excludedRoleId))
            """)
    Page<UserJpaEntity> search(@Param("pattern") String pattern, @Param("status") UserStatus status,
                               @Param("roleId") UUID roleId, @Param("excludedRoleId") UUID excludedRoleId,
                               Pageable pageable);

    /**
     * The ACTIVE accounts assigned to a warehouse that hold at least one of the roles, by name
     * (SCRUM-466). Bounded by the staff of one warehouse, so it is not paged.
     */
    @Query("""
            select u from UserJpaEntity u
            where u.status = com.stockflow.identity.internal.domain.UserStatus.ACTIVE
              and exists (select 1 from UserWarehouseJpaEntity w
                          where w.userId = u.id and w.warehouseId = :warehouseId)
              and exists (select 1 from UserRoleJpaEntity ur, RoleJpaEntity r
                          where ur.userId = u.id and r.id = ur.roleId and r.code in :roleCodes)
            order by lower(coalesce(u.fullName, u.username)), u.id
            """)
    List<UserJpaEntity> findAssignable(@Param("warehouseId") UUID warehouseId,
                                       @Param("roleCodes") Collection<String> roleCodes);

    /**
     * The ACTIVE staff who hold at least one of the roles and may see the data of {@code warehouseId} (SCRUM-147):
     * those whose broadest role scope is ALL, and those assigned to the warehouse. With a {@code null} warehouse
     * only the first group qualifies: system-wide data is for people who are not limited to warehouses. Anybody who
     * also holds the CUSTOMER role is never returned: a customer is not told about internal stock.
     */
    @Query("""
            select u from UserJpaEntity u
            where u.status = com.stockflow.identity.internal.domain.UserStatus.ACTIVE
              and exists (select 1 from UserRoleJpaEntity ur, RoleJpaEntity r
                          where ur.userId = u.id and r.id = ur.roleId and r.code in :roleCodes)
              and not exists (select 1 from UserRoleJpaEntity uc, RoleJpaEntity c
                              where uc.userId = u.id and c.id = uc.roleId and c.code = 'CUSTOMER')
              and (exists (select 1 from UserRoleJpaEntity us, RoleJpaEntity s
                           where us.userId = u.id and s.id = us.roleId
                             and s.dataScope = com.stockflow.common.security.DataScope.ALL)
                   or (:warehouseId is not null
                       and exists (select 1 from UserWarehouseJpaEntity w
                                   where w.userId = u.id and w.warehouseId = :warehouseId)))
            order by u.id
            """)
    List<UserJpaEntity> findStaffWithRolesFor(@Param("roleCodes") Collection<String> roleCodes,
                                              @Param("warehouseId") UUID warehouseId);

    /**
     * The ACTIVE accounts that do not hold CUSTOMER whose display name - the full name, or the username when it is
     * blank - contains the text, by that name, at most {@code limit}. Native for {@code public.unaccent}: a colleague
     * types "nguyen" for "Nguyễn".
     *
     * @param pattern {@code %text%}, its backslash, percent and underscore escaped with a backslash; null for everyone
     */
    @Query(value = """
            SELECT u.* FROM identity.app_user u
             WHERE u.status = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                                WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
               AND (CAST(:pattern AS text) IS NULL
                    OR lower(public.unaccent(coalesce(nullif(btrim(u.full_name), ''), u.username)))
                       LIKE lower(public.unaccent(CAST(:pattern AS text))) ESCAPE '\\')
             ORDER BY lower(coalesce(nullif(btrim(u.full_name), ''), u.username)), u.id
             LIMIT :limit
            """, nativeQuery = true)
    List<UserJpaEntity> findActiveStaffNamed(@Param("pattern") String pattern, @Param("limit") int limit);

    /**
     * The ACTIVE accounts holding at least one of the roles, whatever their data scope or warehouses, by name; never
     * one that also holds CUSTOMER. For the sales-owner plan: who may look after customers, who the managers are.
     */
    @Query("""
            select u from UserJpaEntity u
            where u.status = com.stockflow.identity.internal.domain.UserStatus.ACTIVE
              and exists (select 1 from UserRoleJpaEntity ur, RoleJpaEntity r
                          where ur.userId = u.id and r.id = ur.roleId and r.code in :roleCodes)
              and not exists (select 1 from UserRoleJpaEntity uc, RoleJpaEntity c
                              where uc.userId = u.id and c.id = uc.roleId and c.code = 'CUSTOMER')
            order by lower(coalesce(u.fullName, u.username)), u.id
            """)
    List<UserJpaEntity> findActiveStaffHoldingAnyRole(@Param("roleCodes") Collection<String> roleCodes);

    /** {@link #findActiveStaffHoldingAnyRole} for one account: whether it would be among them. */
    @Query("""
            select count(u) > 0 from UserJpaEntity u
            where u.id = :userId and u.status = com.stockflow.identity.internal.domain.UserStatus.ACTIVE
              and exists (select 1 from UserRoleJpaEntity ur, RoleJpaEntity r
                          where ur.userId = u.id and r.id = ur.roleId and r.code in :roleCodes)
              and not exists (select 1 from UserRoleJpaEntity uc, RoleJpaEntity c
                              where uc.userId = u.id and c.id = uc.roleId and c.code = 'CUSTOMER')
            """)
    boolean isActiveStaffHoldingAnyRole(@Param("userId") UUID userId,
                                        @Param("roleCodes") Collection<String> roleCodes);

    /** Whether the account is ACTIVE and one of its roles grants {@code permission} ({@code resource:ACTION}). */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM identity.app_user u
                             JOIN identity.user_role ur ON ur.user_id = u.id
                             JOIN identity.role_permission rp ON rp.role_id = ur.role_id
                             JOIN identity.permission p ON p.id = rp.permission_id
                            WHERE u.id = :userId AND u.status = 'ACTIVE' AND p.code = :permission)
            """, nativeQuery = true)
    boolean isActiveAndHolds(@Param("userId") UUID userId, @Param("permission") String permission);

    /** The "must change password" flag alone, for the per-request gate: no entity, no joins. Empty for an unknown id. */
    @Query("select u.mustChangePassword from UserJpaEntity u where u.id = :id")
    java.util.Optional<Boolean> findMustChangePassword(@Param("id") UUID id);
}
