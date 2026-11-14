package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.entity.ConversationContextJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link ConversationContextJpaEntity}. */
interface ConversationContextJpaRepository extends BaseJpaRepository<ConversationContextJpaEntity> {

    List<ConversationContextJpaEntity> findByConversationIdOrderByAttachedAtAscIdAsc(UUID conversationId);

    List<ConversationContextJpaEntity> findByConversationIdIn(Collection<UUID> conversationIds);
}
