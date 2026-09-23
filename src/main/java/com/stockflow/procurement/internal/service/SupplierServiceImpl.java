package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SupplierPerformanceSummary;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.procurement.api.SupplierSummary;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import com.stockflow.procurement.internal.repository.SupplierJpaRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class SupplierServiceImpl implements SupplierService {
    private static final List<PurchaseOrderStatus> OPEN = List.of(PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.SENT, PurchaseOrderStatus.PARTIALLY_RECEIVED);
    private static final SortWhitelist SORT = SortWhitelist.of("code", "name", "status", "createdAt", "lastModifiedAt")
            .withDefault("lastModifiedAt", Sort.Direction.DESC);
    private final SupplierJpaRepository suppliers;
    private final Clock clock;

    SupplierServiceImpl(SupplierJpaRepository suppliers, Clock clock) {
        this.suppliers = suppliers;
        this.clock = clock;
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.CREATE, resourceType = "supplier")
    public SupplierSummary create(SaveSupplierCommand c) {
        validate(c);
        String code = normalizeCode(c.code());
        checkUnique(null, code, clean(c.taxCode()));
        var entity = new SupplierJpaEntity(Identifiers.newId(), code, c.name().trim(), clean(c.email()),
                clean(c.phone()), clean(c.taxCode()), SupplierStatus.valueOf(c.status()), clean(c.contactName()),
                c.paymentTermDays(), c.leadTimeDays(), channel(c), clean(c.apiEndpoint()));
        return summary(persist(entity));
    }

    @Override public SupplierSummary update(UUID id, SaveSupplierCommand c) {
        validate(c);
        SupplierJpaEntity entity = loadForUpdate(id);
        String code = normalizeCode(c.code());
        if (!entity.getCode().equalsIgnoreCase(code)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Supplier code is immutable");
        }
        String taxCode = clean(c.taxCode());
        checkUnique(id, code, taxCode);
        SupplierStatus status = SupplierStatus.valueOf(c.status());
        if (entity.getStatus() == SupplierStatus.ACTIVE && status == SupplierStatus.INACTIVE
                && suppliers.hasPurchaseOrdersInStatuses(id, OPEN)) {
            throw new BusinessException(ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS,
                    "Close or cancel all open purchase orders before deactivating this supplier");
        }
        entity.update(c.name().trim(), clean(c.email()), clean(c.phone()), taxCode, status,
                clean(c.contactName()), c.paymentTermDays(), c.leadTimeDays(), channel(c), clean(c.apiEndpoint()));
        return summary(persist(entity));
    }

    @Override public void deactivate(UUID id) {
        SupplierJpaEntity entity = loadForUpdate(id);
        if (entity.getStatus() == SupplierStatus.INACTIVE) return;
        ensureNoOpenOrders(id);
        entity.deactivate();
        persist(entity);
    }

    @Override @Transactional(readOnly = true) public Optional<SupplierSummary> findById(UUID id) {
        return suppliers.findById(id).map(SupplierServiceImpl::summary);
    }

    @Override @Transactional(readOnly = true)
    public PageResponse<SupplierSummary> list(int page, int size, String search, String status, String sort) {
        Specification<SupplierJpaEntity> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (search != null && !search.isBlank()) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(root.get("name")), term), cb.like(cb.lower(root.get("taxCode")), term)));
            }
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), SupplierStatus.valueOf(status)));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return Pages.toResponse(suppliers.findAll(spec, Pages.of(page, size, SORT.parse(sort))).map(SupplierServiceImpl::summary));
    }

    @Override @Transactional(readOnly = true) public SupplierPerformanceSummary performance(UUID id) {
        load(id);
        var p = suppliers.performance(id);
        long rated = p.getOnTimeOrders() + p.getLateOrders();
        long inspected = p.getAcceptedQuantity() + p.getRejectedQuantity();
        return new SupplierPerformanceSummary(id, p.getTotalPurchaseOrders(), p.getFulfilledPurchaseOrders(),
                p.getOnTimeOrders(), p.getLateOrders(), percent(p.getOnTimeOrders(), rated),
                p.getAverageLeadTimeDays() == null ? null : p.getAverageLeadTimeDays().setScale(2, RoundingMode.HALF_UP), p.getAcceptedQuantity(),
                p.getRejectedQuantity(), percent(p.getAcceptedQuantity(), inspected), clock.instant());
    }

    private SupplierJpaEntity load(UUID id) { return suppliers.findById(id).orElseThrow(() ->
            new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + id)); }
    private SupplierJpaEntity loadForUpdate(UUID id) { return suppliers.findByIdForUpdate(id).orElseThrow(() ->
            new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier with id " + id)); }
    private void checkUnique(UUID id, String code, String tax) {
        boolean duplicateCode = id == null ? suppliers.existsByCodeIgnoreCase(code) : suppliers.existsByCodeIgnoreCaseAndIdNot(code, id);
        if (duplicateCode) throw new BusinessException(ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS, "Supplier code already exists: " + code);
        if (tax != null && (id == null ? suppliers.existsByTaxCode(tax) : suppliers.existsByTaxCodeAndIdNot(tax, id)))
            throw new BusinessException(ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS, "Supplier tax code already exists: " + tax);
    }
    private void ensureNoOpenOrders(UUID id) {
        if (suppliers.hasPurchaseOrdersInStatuses(id, OPEN))
            throw new BusinessException(ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS,
                    "Close or cancel all open purchase orders before deactivating this supplier");
    }
    private SupplierJpaEntity persist(SupplierJpaEntity entity) {
        try {
            return suppliers.saveAndFlush(entity);
        } catch (DataIntegrityViolationException race) {
            String detail = String.valueOf(race.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
            ErrorCode code;
            if (detail.contains("uk_supplier_tax_code")) code = ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS;
            else if (detail.contains("uk_supplier_code")) code = ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS;
            else throw race;
            throw new BusinessException(code, code.defaultMessage(), race);
        }
    }
    private static SupplierCommunicationChannel channel(SaveSupplierCommand c) {
        var result = SupplierCommunicationChannel.valueOf(c.communicationChannel());
        if (result == SupplierCommunicationChannel.EMAIL && clean(c.email()) == null)
            throw new IllegalArgumentException("email is required for EMAIL communication");
        if (result == SupplierCommunicationChannel.API && (clean(c.apiEndpoint()) == null || !c.apiEndpoint().startsWith("https://")))
            throw new IllegalArgumentException("an HTTPS apiEndpoint is required for API communication");
        return result;
    }
    private static void validate(SaveSupplierCommand c) {
        new com.stockflow.procurement.internal.domain.SupplierProfile(c.code(), c.name(), c.email(), c.phone(),
                c.taxCode(), c.paymentTermDays(), c.leadTimeDays(), c.communicationChannel(), c.apiEndpoint());
    }
    private static String normalizeCode(String v) { return v.trim().toUpperCase(Locale.ROOT); }
    private static String clean(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static BigDecimal percent(long numerator, long denominator) { return denominator == 0 ? null
            : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP); }
    private static SupplierSummary summary(SupplierJpaEntity e) { return new SupplierSummary(e.getId(), e.getCode(), e.getName(),
            e.getContactName(), e.getEmail(), e.getPhone(), e.getTaxCode(), e.getStatus().name(), e.getPaymentTermDays(),
            e.getLeadTimeDays(), e.getCommunicationChannel().name(), e.getApiEndpoint(), e.getCreatedAt(), e.getLastModifiedAt()); }
}
