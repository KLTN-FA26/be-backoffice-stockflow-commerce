package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantMessageJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssistantMessageJpaRepository extends BaseJpaRepository<AssistantMessageJpaEntity> {

    List<AssistantMessageJpaEntity> findByConversationIdOrderBySequenceAsc(UUID conversationId);

    /**
     * The daily cap ({@code ConversationRepository#questionsSince}), of the questions the user asked: the first question
     * of a proactive conversation is the watch's, under its own cap; what the user asks there afterwards is theirs. The
     * turns an answer produced are stored right
     * after its question, in one write, so the question's next sequence is the first of them: a closing turn there
     * means the provider never replied. Nothing there yet means the answer is still running, and the question counts.
     */
    @Query("""
            select count(m) from AssistantMessageJpaEntity m, AssistantConversationJpaEntity c
            where m.conversationId = c.id and c.ownerUserId = :owner
              and not (c.origin = com.stockflow.assistant.internal.domain.ConversationOrigin.PROACTIVE
                       and m.sequence = 0)
              and m.role = com.stockflow.assistant.internal.domain.MessageRole.USER
              and m.createdAt >= :since
              and not exists (select n.id from AssistantMessageJpaEntity n
                              where n.conversationId = m.conversationId and n.sequence = m.sequence + 1
                                and n.model = :closingModel)""")
    long countQuestionsSince(@Param("owner") UUID owner, @Param("since") Instant since,
                             @Param("closingModel") String closingModel);

    /**
     * The answer {@code messageId} of {@code conversationId}, locked: an assistant turn holding text. Unscoped on
     * purpose, like the conversation's own lock: called only after the caller's ownership of the conversation is
     * established. The lock queues two verdicts on one answer, so the second finds the first's row instead of
     * inserting a second one into {@code uk_answer_feedback_message}; the message itself is never written. Here and not
     * on the verdicts' repository: Spring Data reads a query returning another type than its repository's entity as a
     * DTO projection and rewrites it into a constructor call that does not parse.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from AssistantMessageJpaEntity m
            where m.id = :messageId and m.conversationId = :conversationId
              and m.role = com.stockflow.assistant.internal.domain.MessageRole.ASSISTANT
              and m.text is not null and trim(m.text) <> ''""")
    Optional<AssistantMessageJpaEntity> lockAnswer(@Param("messageId") UUID messageId,
                                                   @Param("conversationId") UUID conversationId);
}
