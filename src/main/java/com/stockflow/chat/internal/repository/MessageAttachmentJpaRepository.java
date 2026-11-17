package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.entity.MessageAttachmentJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link MessageAttachmentJpaEntity}. */
interface MessageAttachmentJpaRepository extends BaseJpaRepository<MessageAttachmentJpaEntity> {

    List<MessageAttachmentJpaEntity> findByMessageIdIn(Collection<UUID> messageIds);
}
