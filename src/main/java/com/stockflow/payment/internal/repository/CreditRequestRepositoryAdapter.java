package com.stockflow.payment.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.payment.internal.domain.CreditRequest;
import com.stockflow.payment.internal.domain.CreditRequestStatus;
import com.stockflow.payment.internal.domain.CreditRequests;
import com.stockflow.payment.internal.entity.CreditRequestJpaEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditRequests} over {@link CreditRequestJpaEntity}. A request is written back against the version it was read
 * at, and a second open request of a customer - two filed at once - is refused by {@code uk_credit_request_open}, which
 * this turns into the business answer rather than a 500.
 */
@Repository
class CreditRequestRepositoryAdapter implements CreditRequests {

    private static final List<CreditRequestStatus> OPEN =
            List.of(CreditRequestStatus.SUBMITTED, CreditRequestStatus.PENDING_APPROVAL);

    private final CreditRequestJpaRepository rows;

    CreditRequestRepositoryAdapter(CreditRequestJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public Optional<CreditRequest> find(UUID id) {
        return rows.findById(id).map(CreditRequestRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<CreditRequest> findForUpdate(UUID id) {
        return rows.findForUpdate(id).map(CreditRequestRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<CreditRequest> findOpen(UUID customerId) {
        return rows.findFirstByCustomerIdAndStatusIn(customerId, OPEN).map(CreditRequestRepositoryAdapter::toDomain);
    }

    @Override
    public CreditRequest save(CreditRequest request) {
        CreditRequestJpaEntity row = rows.findById(request.id()).orElse(null);
        if (row == null) {
            row = new CreditRequestJpaEntity(request.id(), request.customerId(), request.source(),
                    request.requestedLimit(), request.requestedTermDays(), request.currency(), request.reason(),
                    request.submittedBy(), request.submittedAt());
        } else if (row.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Credit request %s changed meanwhile".formatted(request.id()));
        }
        row.progress(request.status(), request.salesNote(), request.salesRecommendedLimit(), request.recommendedBy(),
                request.recommendedAt(), request.approvedLimit(), request.approvedTermDays(), request.decidedBy(),
                request.decidedAt(), request.decisionNote());
        try {
            return toDomain(rows.saveAndFlush(row));
        } catch (DataIntegrityViolationException conflict) {
            if (String.valueOf(conflict.getMostSpecificCause().getMessage()).contains("uk_credit_request_open")) {
                throw new BusinessException(ErrorCode.CREDIT_REQUEST_ALREADY_OPEN,
                        "Customer %s already has an open credit request".formatted(request.customerId()));
            }
            throw conflict;
        }
    }

    @Override
    public PageResponse<CreditRequest> page(UUID customerId, CreditRequestStatus status, int page, int size) {
        List<Specification<CreditRequestJpaEntity>> filters = new ArrayList<>();
        if (customerId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (status != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Specification<CreditRequestJpaEntity> where = Specification.allOf(filters);
        var pageable = Pages.of(page, size, Sort.by(Sort.Order.desc("submittedAt"), Sort.Order.desc("id")));
        return Pages.toResponse(rows.findAll(where, pageable).map(CreditRequestRepositoryAdapter::toDomain));
    }

    private static CreditRequest toDomain(CreditRequestJpaEntity r) {
        return CreditRequest.restore(r.getId(), r.getCustomerId(), r.getSource(), r.getStatus(), r.getRequestedLimit(),
                r.getRequestedTermDays(), r.getCurrency(), r.getReason(), r.getSubmittedBy(), r.getSubmittedAt(),
                r.getSalesNote(), r.getSalesRecommendedLimit(), r.getRecommendedBy(), r.getRecommendedAt(),
                r.getApprovedLimit(), r.getApprovedTermDays(), r.getDecidedBy(), r.getDecidedAt(),
                r.getDecisionNote(), r.getVersion());
    }
}
