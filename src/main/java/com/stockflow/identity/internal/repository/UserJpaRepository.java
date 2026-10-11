package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.UserStatus;
import com.stockflow.identity.internal.entity.UserJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserJpaEntity}. */
public interface UserJpaRepository extends BaseJpaRepository<UserJpaEntity> {

    Optional<UserJpaEntity> findByUsernameIgnoreCase(String username);
    Optional<UserJpaEntity> findByEmailIgnoreCase(String email);

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
}
