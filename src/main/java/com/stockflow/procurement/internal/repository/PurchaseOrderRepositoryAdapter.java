package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.entity.SupplierJpaEntity;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.api.PurchaseOrderStatusCount;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.common.persistence.Specs;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapter: implements the aggregate's domain port plus the list-only search and reporting
 * interfaces on top of Spring Data. Same shape as {@code ProductRepositoryAdapter} — look up,
 * apply, save.
 */
@Repository
class PurchaseOrderRepositoryAdapter implements PurchaseOrderRepository, PurchaseOrderSearchRepository,
        ProcurementReportRepository {

    private static final DateTimeFormatter PO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderJpaRepository jpa;
    private final SupplierJpaRepository suppliers;
    private final PoNumberSequence poNumberSequence;

    @PersistenceContext
    private EntityManager entityManager;

    PurchaseOrderRepositoryAdapter(PurchaseOrderJpaRepository jpa, SupplierJpaRepository suppliers,
                                   PoNumberSequence poNumberSequence) {
        this.jpa = jpa;
        this.suppliers = suppliers;
        this.poNumberSequence = poNumberSequence;
    }

    @Override
    public Optional<PurchaseOrder> findById(PurchaseOrderId id) {
        return jpa.findWithLinesById(id.value()).map(PurchaseOrderPersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(PurchaseOrderId id) {
        return jpa.existsById(id.value());
    }

    @Override
    public String nextPoNumber(LocalDate date) {
        return "PO-%s-%06d".formatted(PO_DATE.format(date), poNumberSequence.nextFor(date));
    }

    @Override
    public Optional<SupplierStatus> supplierStatus(UUID supplierId) {
        return suppliers.findById(supplierId).map(s -> s.getStatus());
    }

    @Override
    public List<PurchaseOrder> findOpenBySupplierAndExpectedAt(UUID supplierId, LocalDate expectedAt) {
        return jpa.findBySupplierIdAndExpectedAtAndStatusNotIn(supplierId, expectedAt,
                        List.of(PurchaseOrderStatus.CANCELLED, PurchaseOrderStatus.CLOSED,
                                PurchaseOrderStatus.CLOSED_SHORT))
                .stream()
                .map(PurchaseOrderPersistenceMapper::toDomain)
                .toList();
    }

    /** Insert or update — the aggregate carries its own id, so look the row up first, same shape
     *  as {@code ProductRepositoryAdapter.save}. */
    @Override
    public PurchaseOrder save(PurchaseOrder order) {
        Optional<PurchaseOrderJpaEntity> existing = jpa.findWithLinesById(order.id().value());
        if (existing.isPresent()) {
            PurchaseOrderJpaEntity managed = existing.get();
            PurchaseOrderPersistenceMapper.applyToEntity(order, managed);
            return PurchaseOrderPersistenceMapper.toDomain(jpa.save(managed));
        }
        return PurchaseOrderPersistenceMapper.toDomain(
                jpa.save(PurchaseOrderPersistenceMapper.toNewEntity(order)));
    }

    @Override
    public Page<PurchaseOrderSummary> search(PurchaseOrderSearchCriteria criteria, Pageable pageable) {
        Specification<PurchaseOrderJpaEntity> spec = Specification
                .<PurchaseOrderJpaEntity>where(Specs.eq("supplierId", criteria.supplierId()))
                .and(Specs.in("status", criteria.statuses()));
        return jpa.findAll(spec, pageable).map(PurchaseOrderPersistenceMapper::toSummaryWithoutLines);
    }

    /** SCRUM-119: orders that are not yet a commitment ({@code DRAFT}) or never fulfilled
     *  ({@code CANCELLED}) do not count as spend — see {@code ProcurementService.supplierSpend}'s
     *  javadoc for why. */
    private static final List<PurchaseOrderStatus> NON_SPEND_STATUSES =
            List.of(PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CANCELLED);

    @Override
    public List<PurchaseOrderStatusCount> statusDashboard() {
        Map<PurchaseOrderStatus, Long> counts = jpa.countByStatus().stream()
                .collect(Collectors.toMap(row -> (PurchaseOrderStatus) row[0], row -> (Long) row[1]));
        return Stream.of(PurchaseOrderStatus.values())
                .map(status -> new PurchaseOrderStatusCount(status.name(), counts.getOrDefault(status, 0L)))
                .toList();
    }

    /**
     * Built with the Criteria API rather than a static {@code @Query} string: a static JPQL
     * {@code (:x is null or col >= :x)} pattern binds {@code :x} as a real parameter even when the
     * filter is absent, and Postgres's extended query protocol cannot always infer that parameter's
     * type from an {@code is null} check alone — reproduced directly against psql while building
     * this method, both as a prepare-time "could not determine data type of parameter" error and,
     * after wrapping the parameter in an explicit {@code cast(:x as date)} to fix that, as a second
     * failure ("cannot cast type bytea to date") for the equally real case of the filter being
     * absent. Building the predicate list in Java and simply never adding a predicate for a filter
     * that is null — same idea as {@link Specs}, used two methods above — sidesteps the whole
     * class of problem: an absent filter never becomes a bind parameter at all.
     */
    @Override
    public Page<SupplierSpendSummary> supplierSpend(SupplierSpendCriteria criteria, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<SupplierSpendRow> query = cb.createQuery(SupplierSpendRow.class);
        Root<PurchaseOrderJpaEntity> root = query.from(PurchaseOrderJpaEntity.class);
        query.select(cb.construct(SupplierSpendRow.class,
                        root.get("supplierId"), cb.sum(root.get("totalAmount")), cb.count(root)))
                .where(spendPredicates(cb, root, criteria).toArray(new Predicate[0]))
                .groupBy(root.get("supplierId"))
                .orderBy(cb.desc(cb.sum(root.get("totalAmount"))));
        List<SupplierSpendRow> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<PurchaseOrderJpaEntity> countRoot = countQuery.from(PurchaseOrderJpaEntity.class);
        countQuery.select(cb.countDistinct(countRoot.get("supplierId")))
                .where(spendPredicates(cb, countRoot, criteria).toArray(new Predicate[0]));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        Map<UUID, SupplierJpaEntity> suppliersById = suppliers
                .findAllById(content.stream().map(SupplierSpendRow::supplierId).toList())
                .stream()
                .collect(Collectors.toMap(SupplierJpaEntity::getId, Function.identity()));
        List<SupplierSpendSummary> summaries = content.stream()
                .map(row -> {
                    SupplierJpaEntity supplier = suppliersById.get(row.supplierId());
                    return new SupplierSpendSummary(row.supplierId(), supplier.getCode(), supplier.getName(),
                            row.totalSpend(), row.orderCount());
                })
                .toList();
        return new PageImpl<>(summaries, pageable, total);
    }

    private List<Predicate> spendPredicates(CriteriaBuilder cb, Root<PurchaseOrderJpaEntity> root,
                                            SupplierSpendCriteria criteria) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.not(root.get("status").in(NON_SPEND_STATUSES)));
        if (criteria.supplierId() != null) {
            predicates.add(cb.equal(root.get("supplierId"), criteria.supplierId()));
        }
        if (criteria.expectedAtFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("expectedAt"), criteria.expectedAtFrom()));
        }
        if (criteria.expectedAtTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("expectedAt"), criteria.expectedAtTo()));
        }
        return predicates;
    }
}
