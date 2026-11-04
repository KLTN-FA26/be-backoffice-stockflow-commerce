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

    long countByOwnerUserIdAndOriginAndCreatedAtGreaterThanEqual(java.util.UUID ownerUserId,
            com.stockflow.assistant.internal.domain.ConversationOrigin origin, java.time.Instant since);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            delete from AssistantConversationJpaEntity c
            where c.id = :id and c.ownerUserId = :owner and c.origin = :origin and c.messageCount = 0""")
    int deleteEmptyProactive(@org.springframework.data.repository.query.Param("id") java.util.UUID id,
                             @org.springframework.data.repository.query.Param("owner") java.util.UUID owner,
                             @org.springframework.data.repository.query.Param("origin")
                             com.stockflow.assistant.internal.domain.ConversationOrigin origin);

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
     *
     * <p>A claim left behind by a process killed mid-answer, or by a failed release, must not keep a conversation past
     * its retention: a claim older than the cutoff is long past any lease, so it does not protect the row. A row
     * locked right now (a question being claimed or stored) is skipped rather than waited on, and goes in a later
     * run; without {@code SKIP LOCKED} the delete would remove a conversation whose claim committed after the
     * subquery read it.</p>
     */
    @Modifying
    @Query(value = """
            DELETE FROM assistant.conversation WHERE id IN (
                SELECT id FROM assistant.conversation
                WHERE last_activity_at < :cutoff AND (answering_since IS NULL OR answering_since < :cutoff)
                ORDER BY last_activity_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)""", nativeQuery = true)
    int deleteIdleBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Empty proactive conversations opened before {@code cutoff} and never asked in: a look stopped right there. */
    @Modifying
    @Query(value = """
            DELETE FROM assistant.conversation WHERE id IN (
                SELECT id FROM assistant.conversation
                WHERE origin = 'PROACTIVE' AND message_count = 0 AND answering_since IS NULL
                  AND created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)""", nativeQuery = true)
    int deleteAbandonedProactiveBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
