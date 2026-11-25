package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignProjectQuoteRequestLineJpaEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignProjectQuoteRequestLineJpaEntity}. A plain {@link JpaRepository}: the row
 * has a composite key and no version.
 */
public interface DesignProjectQuoteRequestLineJpaRepository extends
        JpaRepository<DesignProjectQuoteRequestLineJpaEntity, DesignProjectQuoteRequestLineJpaEntity.Key> {

    List<DesignProjectQuoteRequestLineJpaEntity> findByRequestIdIn(Collection<java.util.UUID> requestIds);

    /**
     * Whether an OPEN project quote request names the design, which then stays in its project until the sales staff
     * answer the request (BR-23.4). Read only: the caller holds the project's lock, which asking a quote takes too.
     */
    @Query("select count(l) > 0 from DesignProjectQuoteRequestLineJpaEntity l, DesignProjectQuoteRequestJpaEntity r "
            + "where r.id = l.requestId and l.draftId = :draftId "
            + "and r.status = com.stockflow.design.internal.domain.QuoteRequestStatus.OPEN")
    boolean inOpenRequest(@Param("draftId") UUID draftId);
}
