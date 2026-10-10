package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.entity.UserRoleJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserRoleJpaEntity}. */
public interface UserRoleJpaRepository extends BaseJpaRepository<UserRoleJpaEntity> {

    Optional<UserRoleJpaEntity> findByUserIdAndRoleId(UUID userId, UUID roleId);

    List<UserRoleJpaEntity> findByUserId(UUID userId);

    /** The roles of a page of users in one query, for the user list. */
    List<UserRoleJpaEntity> findByUserIdIn(Collection<UUID> userIds);

    long countByRoleId(UUID roleId);

    /**
     * How many ACTIVE accounts other than {@code exceptUserId} hold the role — the "last System
     * Admin" guard. Takes the holders' rows {@code FOR UPDATE} first, through the join, so that two
     * administrators demoting each other at the same moment cannot both see the other one remain.
     */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT u.id FROM identity.user_role ur
                JOIN identity.app_user u ON u.id = ur.user_id
                WHERE ur.role_id = :roleId AND u.status = 'ACTIVE' AND u.id <> :exceptUserId
                FOR UPDATE OF ur
            ) holders
            """, nativeQuery = true)
    long countOtherActiveHolders(@Param("roleId") UUID roleId, @Param("exceptUserId") UUID exceptUserId);
}
