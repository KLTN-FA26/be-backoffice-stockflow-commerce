package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignQuoteRequestJpaEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Lock;
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

    /** The request, locked - after its design's, as every writer of a request locks them - and read as it is now. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DesignQuoteRequestJpaEntity r where r.id = :id")
    Optional<DesignQuoteRequestJpaEntity> lockById(@Param("id") UUID id);

    /**
     * The sales staff's list: every customer's requests or one customer's - the design's, read in the design schema
     * itself - in any status or one. A specification rather than {@code (:x is null or ...)}: an absent filter then
     * binds no parameter, whose type PostgreSQL could not infer.
     */
    static Specification<DesignQuoteRequestJpaEntity> listed(UUID customerId, QuoteRequestStatus status) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                var designs = query.subquery(UUID.class);
                var design = designs.from(DesignDraftJpaEntity.class);
                designs.select(design.get("id")).where(cb.equal(design.get("customerId"), customerId));
                where.add(root.get("draftId").in(designs));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
