package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.domain.Visibility;
import com.stockflow.chat.internal.entity.MessageJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link MessageJpaEntity}. Every finder that reads messages for someone takes the
 * visibilities they may see: there is no finder here that a careless caller could use to read internal notes for a
 * customer by accident, apart from the retry lookup, which the adapter guards.
 */
interface MessageJpaRepository extends BaseJpaRepository<MessageJpaEntity> {

    @Query("select m from MessageJpaEntity m where m.id = :id and m.visibility in :visible")
    Optional<MessageJpaEntity> findVisible(@Param("id") UUID id, @Param("visible") Collection<Visibility> visible);

    Optional<MessageJpaEntity> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    @Query("""
            select m from MessageJpaEntity m
             where m.conversationId = :conversation and m.visibility in :visible and m.seq < :before
             order by m.seq desc""")
    List<MessageJpaEntity> before(@Param("conversation") UUID conversationId,
                                  @Param("visible") Collection<Visibility> visible, @Param("before") long beforeSeq,
                                  Pageable page);

    @Query("""
            select m from MessageJpaEntity m
             where m.conversationId = :conversation and m.visibility in :visible and m.seq > :after
             order by m.seq asc""")
    List<MessageJpaEntity> after(@Param("conversation") UUID conversationId,
                                 @Param("visible") Collection<Visibility> visible, @Param("after") long afterSeq,
                                 Pageable page);
}
