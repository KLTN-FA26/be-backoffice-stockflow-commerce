package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.entity.ConversationJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link ConversationJpaEntity}; the lists are native SQL in the adapter. */
interface ConversationJpaRepository extends BaseJpaRepository<ConversationJpaEntity> {
}
