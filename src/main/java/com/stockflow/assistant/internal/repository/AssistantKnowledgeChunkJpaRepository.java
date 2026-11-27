package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.entity.AssistantKnowledgeChunkJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.List;

interface AssistantKnowledgeChunkJpaRepository extends BaseJpaRepository<AssistantKnowledgeChunkJpaEntity> {

    List<AssistantKnowledgeChunkJpaEntity> findBySource(String source);
}
