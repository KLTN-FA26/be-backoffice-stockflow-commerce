package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantToolCallJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;
import java.util.UUID;

interface AssistantToolCallJpaRepository extends BaseJpaRepository<AssistantToolCallJpaEntity> {

    /** In the order they ran: ids are time-ordered (UUIDv7) and written in call order. */
    List<AssistantToolCallJpaEntity> findByConversationIdOrderByIdAsc(UUID conversationId);
}
