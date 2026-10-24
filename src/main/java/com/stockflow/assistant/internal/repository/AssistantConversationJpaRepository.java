package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantConversationJpaEntity;
import com.stockflow.common.security.ScopedJpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AssistantConversationJpaRepository extends ScopedJpaRepository<AssistantConversationJpaEntity> {

    @Override
    default AssistantConversationJpaEntity scopePrototype() {
        return AssistantConversationJpaEntity.SCOPE_PROTOTYPE;
    }

    /** Unscoped on purpose: called only after {@code findByIdInScope} has established the caller owns it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AssistantConversationJpaEntity c where c.id = :id")
    Optional<AssistantConversationJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The retention sweep. Idle conversations go whole: messages and tool calls follow by {@code ON DELETE CASCADE}.
     * One being answered right now is left for the next run.
     */
    @Modifying
    @Query(value = """
            DELETE FROM assistant.conversation WHERE id IN (
                SELECT id FROM assistant.conversation
                WHERE last_activity_at < :cutoff AND answering_since IS NULL
                ORDER BY last_activity_at
                LIMIT :limit)""", nativeQuery = true)
    int deleteIdleBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
