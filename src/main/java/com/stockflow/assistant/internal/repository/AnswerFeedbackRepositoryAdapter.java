package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.AnswerFeedback;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.entity.AssistantAnswerFeedbackJpaEntity;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link AnswerFeedbackRepository} over the verdicts' Spring Data repository, and the messages' for the lock.
 *
 * <p><b>The answer is locked before its verdict is read.</b> Find-then-insert alone lets two clicks on one answer both
 * find nothing and both insert, and the second fails on the unique key. Under the lock the second waits, then finds
 * the first's row and rewrites it.</p>
 */
@Repository
class AnswerFeedbackRepositoryAdapter implements AnswerFeedbackRepository {

    private final AssistantAnswerFeedbackJpaRepository feedback;
    private final AssistantMessageJpaRepository messages;

    AnswerFeedbackRepositoryAdapter(AssistantAnswerFeedbackJpaRepository feedback,
                                    AssistantMessageJpaRepository messages) {
        this.feedback = feedback;
        this.messages = messages;
    }

    @Override
    public boolean record(UUID conversationId, UUID messageId, UUID ownerUserId, AnswerFeedback given) {
        if (messages.lockAnswer(messageId, conversationId).isEmpty()) {
            return false;
        }
        AssistantAnswerFeedbackJpaEntity row = feedback
                .findAllInScope(Specs.eq("messageId", messageId)).stream().findFirst()
                .orElseGet(() -> new AssistantAnswerFeedbackJpaEntity(Identifiers.newId(), messageId, ownerUserId));
        row.give(given.verdict(), given.reason());
        feedback.save(row);
        return true;
    }

    @Override
    public Map<UUID, FeedbackVerdict> verdictsOf(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return feedback.findAllInScope(Specs.in("messageId", messageIds)).stream()
                .collect(Collectors.toMap(AssistantAnswerFeedbackJpaEntity::getMessageId,
                        AssistantAnswerFeedbackJpaEntity::getVerdict));
    }
}
