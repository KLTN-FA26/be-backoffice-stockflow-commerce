package com.stockflow.order.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.order.internal.entity.QuoteJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface QuoteJpaRepository extends BaseJpaRepository<QuoteJpaEntity> {

    @Query("select distinct q from QuoteJpaEntity q left join fetch q.lines where q.id = :id")
    Optional<QuoteJpaEntity> findByIdWithLines(@Param("id") UUID id);

    @Query("select q.id from QuoteJpaEntity q where q.requestId = :requestId")
    Optional<UUID> findIdByRequestId(@Param("requestId") UUID requestId);

    /** Served by {@code uk_quote_converted_order}. */
    @Query("select q.id from QuoteJpaEntity q where q.convertedOrderId = :orderId")
    Optional<UUID> findIdByConvertedOrderId(@Param("orderId") UUID orderId);

    /** Served by {@code ix_quote_sent_validity}. */
    @Query("""
            select q.id from QuoteJpaEntity q
             where q.status = com.stockflow.order.internal.domain.QuoteStatus.SENT and q.validUntil <= :now
             order by q.validUntil""")
    List<UUID> findSentExpiredBy(@Param("now") Instant now, Pageable limit);
}
