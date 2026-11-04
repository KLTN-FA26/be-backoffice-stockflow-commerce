package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantAnswerFeedbackJpaEntity;
import com.stockflow.common.security.ScopedJpaRepository;

interface AssistantAnswerFeedbackJpaRepository extends ScopedJpaRepository<AssistantAnswerFeedbackJpaEntity> {

    @Override
    default AssistantAnswerFeedbackJpaEntity scopePrototype() {
        return AssistantAnswerFeedbackJpaEntity.SCOPE_PROTOTYPE;
    }
}
