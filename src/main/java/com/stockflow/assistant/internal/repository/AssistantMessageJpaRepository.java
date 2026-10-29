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

    /**
     * The daily cap ({@code ConversationRepository#questionsSince}). The turns an answer produced are stored right
     * after its question, in one write, so the question's next sequence is the first of them: a closing turn there
     * means the provider never replied. Nothing there yet means the answer is still running, and the question counts.
     */
    @Query("""
            select count(m) from AssistantMessageJpaEntity m, AssistantConversationJpaEntity c
            where m.conversationId = c.id and c.ownerUserId = :owner
              and m.role = com.stockflow.assistant.internal.domain.MessageRole.USER
              and m.createdAt >= :since
              and not exists (select n.id from AssistantMessageJpaEntity n
                              where n.conversationId = m.conversationId and n.sequence = m.sequence + 1
                                and n.model = :closingModel)""")
    long countQuestionsSince(@Param("owner") UUID owner, @Param("since") Instant since,
                             @Param("closingModel") String closingModel);
}
