package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectQuoteRequestJpaEntity;
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

    /**
     * The open request whose lines name the design, if any: at most one, since a design is in one project and a project
     * has one open request. Read before the request is locked, and checked again under the lock.
     */
    @Query(value = "select r.* from design.design_project_quote_request r "
            + "join design.design_project_quote_request_line l on l.request_id = r.id "
            + "where l.draft_id = :designId and r.status = 'OPEN'", nativeQuery = true)
    Optional<DesignProjectQuoteRequestJpaEntity> findOpenContaining(@Param("designId") UUID designId);

    /**
     * The designs of the request not approved yet, read from the database as committed now - not from the entities this
     * transaction holds - so that, of two approvals of a request's last two designs queued on its lock, the second sees
     * the first's and only it finds none left (12 BR-25: Sales are told once).
     */
    @Query(value = "select l.draft_id from design.design_project_quote_request_line l "
            + "join design.design_draft d on d.id = l.draft_id "
            + "where l.request_id = :requestId and d.status <> 'APPROVED' order by l.draft_id", nativeQuery = true)
    List<UUID> unapprovedDesigns(@Param("requestId") UUID requestId);

    /** A project's quote requests, the newest first. */
    List<DesignProjectQuoteRequestJpaEntity> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId);

    /**
     * The sales staff's list: every customer's requests or one customer's - the project's, read in the design schema
     * itself - in any status or one, as {@link DesignQuoteRequestJpaRepository#listed} lists a single design's. A
     * specification rather than {@code (:x is null or ...)}: an absent filter then binds no parameter, whose type
     * PostgreSQL could not infer, and the page's count is taken after the filter.
     */
    static Specification<DesignProjectQuoteRequestJpaEntity> listed(UUID customerId, QuoteRequestStatus status) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                var projects = query.subquery(UUID.class);
                var project = projects.from(DesignProjectJpaEntity.class);
                projects.select(project.get("id")).where(cb.equal(project.get("customerId"), customerId));
                where.add(root.get("projectId").in(projects));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
