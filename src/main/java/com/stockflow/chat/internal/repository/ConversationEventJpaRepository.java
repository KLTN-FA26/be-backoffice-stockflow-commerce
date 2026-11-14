package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.entity.ConversationEventJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link ConversationEventJpaEntity}, the append-only log. */
interface ConversationEventJpaRepository extends BaseJpaRepository<ConversationEventJpaEntity> {

    List<ConversationEventJpaEntity> findByConversationIdOrderByOccurredAtAscIdAsc(UUID conversationId);
}
