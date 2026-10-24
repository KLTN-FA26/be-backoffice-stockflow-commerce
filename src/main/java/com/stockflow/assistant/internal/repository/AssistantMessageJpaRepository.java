package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantMessageJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AssistantMessageJpaRepository extends BaseJpaRepository<AssistantMessageJpaEntity> {

    List<AssistantMessageJpaEntity> findByConversationIdOrderBySequenceAsc(UUID conversationId);

    @Query("""
            select count(m) from AssistantMessageJpaEntity m, AssistantConversationJpaEntity c
            where m.conversationId = c.id and c.ownerUserId = :owner
              and m.role = com.stockflow.assistant.internal.domain.MessageRole.USER
              and m.createdAt >= :since""")
    long countQuestionsSince(@Param("owner") UUID owner, @Param("since") Instant since);
}
