package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.domain.SessionEndReason;
import com.stockflow.identity.internal.entity.UserSessionJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserSessionJpaEntity}. */
public interface UserSessionJpaRepository extends BaseJpaRepository<UserSessionJpaEntity> {

    Optional<UserSessionJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Page<UserSessionJpaEntity> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
            UUID userId, Instant now, Pageable pageable);

    /**
     * Ends every live session of a user except {@code keep} (may be null to end them all) in one
     * statement. A bulk update does not go through Hibernate, so it bumps the version and audit
     * column itself; the persistence context must not hold these rows, which holds because it is
     * only called from the identity service before anything loads them.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserSessionJpaEntity s
               set s.revokedAt = :now, s.revokedReason = :reason,
                   s.lastModifiedAt = :now, s.version = s.version + 1
             where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now
               and (:keep is null or s.id <> :keep)
            """)
    int revokeLive(@Param("userId") UUID userId, @Param("keep") UUID keep,
                   @Param("now") Instant now, @Param("reason") SessionEndReason reason);

    @Modifying
    @Query("delete from UserSessionJpaEntity s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
