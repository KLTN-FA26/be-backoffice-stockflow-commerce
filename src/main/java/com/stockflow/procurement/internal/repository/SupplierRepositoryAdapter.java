package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierMetrics;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
class SupplierRepositoryAdapter implements SupplierRepository {
    private static final List<PurchaseOrderStatus> OPEN = List.of(PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT, PurchaseOrderStatus.PARTIALLY_RECEIVED);
    private static final SortWhitelist SORT = SortWhitelist.of("code", "name", "status", "createdAt", "lastModifiedAt")
            .withDefault("lastModifiedAt", Sort.Direction.DESC);
    private final SupplierJpaRepository jpa;
    private final EntityManager entityManager;
    SupplierRepositoryAdapter(SupplierJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa; this.entityManager = entityManager;
    }
    @Override public Optional<Supplier> findById(UUID id) { return jpa.findById(id).map(SupplierRepositoryAdapter::domain); }
    @Override public Optional<Supplier> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return domain(entity);
        });
    }
    @Override public boolean hasOpenOrders(UUID id) { return jpa.hasPurchaseOrdersInStatuses(id, OPEN); }
    @Override public boolean codeExists(String code, UUID id) {
        return id == null ? jpa.existsByCodeIgnoreCase(code) : jpa.existsByCodeIgnoreCaseAndIdNot(code, id);
    }
    @Override public boolean taxCodeExists(String tax, UUID id) {
        return tax != null && (id == null ? jpa.existsByTaxCode(tax) : jpa.existsByTaxCodeAndIdNot(tax, id));
    }
    @Override public Supplier save(Supplier supplier) {
        var d = supplier.details();
        var entity = jpa.findById(supplier.id()).orElseGet(() -> new SupplierJpaEntity(supplier.id(), d.code(),
                d.name(), d.email(), d.phone(), d.taxCode(), d.status(), d.contactName(), d.paymentTermDays(),
                d.leadTimeDays(), d.communicationChannel(), d.apiEndpoint()));
        entity.update(d.name(), d.email(), d.phone(), d.taxCode(), d.status(), d.contactName(),
                d.paymentTermDays(), d.leadTimeDays(), d.communicationChannel(), d.apiEndpoint());
        try { return domain(jpa.saveAndFlush(entity)); }
        catch (DataIntegrityViolationException race) {
            String detail = String.valueOf(race.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            ErrorCode error;
            if (detail.contains("uk_supplier_tax_code")) error = ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS;
            else if (detail.contains("uk_supplier_code")) error = ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS;
            else throw race;
            throw new BusinessException(error, error.defaultMessage(), race);
        }
    }
    @Override public PageResponse<Supplier> list(int page, int size, String search, SupplierStatus status, String sort) {
        Specification<SupplierJpaEntity> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("code")), term, '\\'),
                        cb.like(cb.lower(root.get("name")), term, '\\'), cb.like(cb.lower(root.get("taxCode")), term, '\\')));
            }
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return Pages.toResponse(jpa.findAll(spec, Pages.of(page, size, SORT.parse(sort))).map(SupplierRepositoryAdapter::domain));
    }
    @Override public SupplierMetrics performance(UUID id) {
        var p = jpa.performance(id, BusinessCalendar.ZONE_NAME);
        return new SupplierMetrics(p.getTotalPurchaseOrders(), p.getFulfilledPurchaseOrders(), p.getOnTimeOrders(),
                p.getLateOrders(), p.getAverageLeadTimeDays(), p.getAcceptedQuantity(), p.getRejectedQuantity());
    }
    private static Supplier domain(SupplierJpaEntity e) {
        return new Supplier(e.getId(), new SupplierDetails(e.getCode(), e.getName(), e.getContactName(), e.getEmail(),
                e.getPhone(), e.getTaxCode(), e.getStatus(), e.getPaymentTermDays(), e.getLeadTimeDays(),
                e.getCommunicationChannel(), e.getApiEndpoint()), e.getCreatedAt(), e.getLastModifiedAt());
    }
}
