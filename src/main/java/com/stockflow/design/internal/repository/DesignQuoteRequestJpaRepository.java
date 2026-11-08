package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignQuoteRequestJpaEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignQuoteRequestJpaEntity}; callers decide who may see a request. */
public interface DesignQuoteRequestJpaRepository extends BaseJpaRepository<DesignQuoteRequestJpaEntity> {

    /** The design's open request, if any (at most one: uk_design_quote_request_open). */
    @Query("select r from DesignQuoteRequestJpaEntity r where r.draftId = :draftId "
            + "and r.status = com.stockflow.design.internal.domain.QuoteRequestStatus.OPEN")
    Optional<DesignQuoteRequestJpaEntity> findOpen(@Param("draftId") UUID draftId);

    /** The design's requests, the newest first (ix_design_quote_request_draft). */
    List<DesignQuoteRequestJpaEntity> findByDraftIdOrderByCreatedAtDescIdDesc(UUID draftId);

    /**
     * The sales staff's list, the newest first: every customer's requests or one customer's, in any status or one. The
     * customer is the design's, read in the design schema itself.
     */
    @Query(value = "select r from DesignQuoteRequestJpaEntity r where (:status is null or r.status = :status) "
            + "and (:customerId is null or r.draftId in (select d.id from DesignDraftJpaEntity d "
            + "where d.customerId = :customerId)) order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from DesignQuoteRequestJpaEntity r where (:status is null "
                    + "or r.status = :status) and (:customerId is null or r.draftId in (select d.id from "
                    + "DesignDraftJpaEntity d where d.customerId = :customerId))")
    Page<DesignQuoteRequestJpaEntity> list(@Param("customerId") UUID customerId,
                                           @Param("status") QuoteRequestStatus status, Pageable page);
}
