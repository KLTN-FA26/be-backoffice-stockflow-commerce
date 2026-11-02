package com.stockflow.identity.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.identity.internal.entity.UserStatusChangeJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link UserStatusChangeJpaEntity}. */
public interface UserStatusChangeJpaRepository extends BaseJpaRepository<UserStatusChangeJpaEntity> {

    /** The history of one account, newest first. Paged: it only ever grows. */
    Page<UserStatusChangeJpaEntity> findByUserIdOrderByChangedAtDescIdDesc(UUID userId, Pageable pageable);

    /** The latest change of each given account that has one, in one query (the user list is a page of them). */
    @Query("""
            select c from UserStatusChangeJpaEntity c
            where c.userId in :userIds
              and not exists (select 1 from UserStatusChangeJpaEntity n
                              where n.userId = c.userId
                                and (n.changedAt > c.changedAt or (n.changedAt = c.changedAt and n.id > c.id)))
            """)
    List<UserStatusChangeJpaEntity> findLatestOf(@Param("userIds") Collection<UUID> userIds);

    /** The latest change of one account, if any. */
    default Optional<UserStatusChangeJpaEntity> findLatestOf(UUID userId) {
        return findLatestOf(List.of(userId)).stream().findFirst();
    }
}
