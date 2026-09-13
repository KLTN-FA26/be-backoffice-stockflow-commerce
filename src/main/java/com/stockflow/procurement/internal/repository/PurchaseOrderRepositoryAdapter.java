package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.entity.PurchaseOrderJpaEntity;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderId;
import com.stockflow.procurement.internal.domain.PurchaseOrderRepository;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.common.persistence.Specs;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter: implements both the aggregate's domain port and the list-only search interface on top
 * of Spring Data. Same shape as {@code ProductRepositoryAdapter} — look up, apply, save.
 */
@Repository
class PurchaseOrderRepositoryAdapter implements PurchaseOrderRepository, PurchaseOrderSearchRepository {

    private static final DateTimeFormatter PO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PurchaseOrderJpaRepository jpa;
    private final SupplierJpaRepository suppliers;
    private final PoNumberSequence poNumberSequence;

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
}
