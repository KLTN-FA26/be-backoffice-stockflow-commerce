package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.entity.CannedResponseJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link CannedResponseJpaEntity}. */
interface CannedResponseJpaRepository extends BaseJpaRepository<CannedResponseJpaEntity> {

    @Query("""
            select r from CannedResponseJpaEntity r
             where r.archivedAt is null and (r.ownerId is null or r.ownerId = :agent)
             order by case when r.ownerId is null then 0 else 1 end, lower(r.title), r.id""")
    List<CannedResponseJpaEntity> visibleTo(@Param("agent") UUID agentId);

    @Query("""
            select count(r) > 0 from CannedResponseJpaEntity r
             where r.archivedAt is null and r.shortcut = :shortcut and r.id <> :id
               and ((:owner is null and r.ownerId is null) or r.ownerId = :owner)""")
    boolean shortcutTaken(@Param("owner") UUID ownerId, @Param("shortcut") String shortcut, @Param("id") UUID id);
}
