package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantToolCallJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AssistantToolCallJpaRepository extends BaseJpaRepository<AssistantToolCallJpaEntity> {

    /** In the order they ran: ids are time-ordered (UUIDv7) and written in call order. */
    List<AssistantToolCallJpaEntity> findByConversationIdOrderByIdAsc(UUID conversationId);

    /** Tool runs that belong to no conversation, older than {@code cutoff}, oldest first, at most {@code limit}. */
    @Modifying
    @Query(value = """
            DELETE FROM assistant.tool_call WHERE id IN (
                SELECT id FROM assistant.tool_call
                WHERE conversation_id IS NULL AND created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit)""", nativeQuery = true)
    int deleteStandaloneBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
