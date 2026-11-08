package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignProjectQuoteRequestJpaEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignProjectQuoteRequestJpaEntity}; callers decide who may see a request. */
public interface DesignProjectQuoteRequestJpaRepository extends BaseJpaRepository<DesignProjectQuoteRequestJpaEntity> {

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
