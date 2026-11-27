package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.StaffCoverJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link StaffCoverJpaEntity}; reached through {@link StaffCoverRepositoryAdapter}. */
interface StaffCoverJpaRepository extends BaseJpaRepository<StaffCoverJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StaffCoverJpaEntity c where c.id = :id")
    Optional<StaffCoverJpaEntity> lockById(@Param("id") UUID id);

    @Query("""
            select count(c) > 0 from StaffCoverJpaEntity c
            where c.userId = :userId and c.status = 'ACTIVE' and c.fromDate <= :to and c.toDate >= :from
            """)
    boolean overlapsActive(@Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Only covers whose stand-in is still ACTIVE: a cover by someone who cannot sign in covers nothing. */
    @Query("""
            select c from StaffCoverJpaEntity c
            where c.userId in :userIds and c.status = 'ACTIVE' and c.fromDate <= :day and c.toDate >= :day
              and exists (select 1 from UserJpaEntity u where u.id = c.coverUserId
                          and u.status = com.stockflow.identity.internal.domain.UserStatus.ACTIVE)
            """)
    List<StaffCoverJpaEntity> findActiveOn(@Param("userIds") Collection<UUID> userIds, @Param("day") LocalDate day);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StaffCoverJpaEntity c where c.coverUserId = :coverUserId and c.status = 'ACTIVE' order by c.id")
    List<StaffCoverJpaEntity> lockActiveStandingIn(@Param("coverUserId") UUID coverUserId);

    /** Every cover, or one absent person's (a null {@code userId}), newest first. */
    @Query("""
            select c from StaffCoverJpaEntity c
            where (:everyone = true or c.userId = :userId)
            order by c.fromDate desc, c.id desc
            """)
    List<StaffCoverJpaEntity> list(@Param("everyone") boolean everyone, @Param("userId") UUID userId, Pageable page);

    /** {@link #list} of the covers not cancelled that end on {@code activeOn} or later. */
    @Query("""
            select c from StaffCoverJpaEntity c
            where (:everyone = true or c.userId = :userId) and c.status = 'ACTIVE' and c.toDate >= :activeOn
            order by c.fromDate desc, c.id desc
            """)
    List<StaffCoverJpaEntity> listActive(@Param("everyone") boolean everyone, @Param("userId") UUID userId,
                                         @Param("activeOn") LocalDate activeOn, Pageable page);
}
