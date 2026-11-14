package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectQuoteRequestJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignProjectQuoteRequestJpaEntity}; callers decide who may see a request. */
public interface DesignProjectQuoteRequestJpaRepository extends BaseJpaRepository<DesignProjectQuoteRequestJpaEntity> {

    /**
     * The project's open request, if any (at most one: {@code uk_design_project_quote_request_open}). Read under the
     * project's lock, which every writer of its requests takes first.
     */
    @Query("select r from DesignProjectQuoteRequestJpaEntity r where r.projectId = :projectId "
            + "and r.status = com.stockflow.design.internal.domain.QuoteRequestStatus.OPEN")
    Optional<DesignProjectQuoteRequestJpaEntity> findOpen(@Param("projectId") UUID projectId);

    /** The request, locked - after its project's - and read as it is now. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DesignProjectQuoteRequestJpaEntity r where r.id = :id")
    Optional<DesignProjectQuoteRequestJpaEntity> lockById(@Param("id") UUID id);

    /** A project's quote requests, the newest first. */
    List<DesignProjectQuoteRequestJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    /** The sales staff's list of every customer, the newest first. */
    Page<DesignProjectQuoteRequestJpaEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    /**
     * The sales staff's list of one customer - the project's - the newest first. Two methods rather than
     * {@code (:customerId is null or ...)}: an absent filter would bind a null PostgreSQL cannot type.
     */
    @Query(value = "select r from DesignProjectQuoteRequestJpaEntity r where r.projectId in ("
            + "select p.id from DesignProjectJpaEntity p where p.customerId = :customerId) "
            + "order by r.createdAt desc, r.id desc",
            countQuery = "select count(r) from DesignProjectQuoteRequestJpaEntity r where r.projectId in ("
                    + "select p.id from DesignProjectJpaEntity p where p.customerId = :customerId)")
    Page<DesignProjectQuoteRequestJpaEntity> ofCustomer(@Param("customerId") UUID customerId, Pageable page);
}
