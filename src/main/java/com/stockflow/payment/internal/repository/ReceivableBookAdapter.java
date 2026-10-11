package com.stockflow.payment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.entity.CustomerTransferJpaEntity;
import com.stockflow.payment.internal.entity.ReceivableJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ReceivableBookAdapter implements ReceivableBook, ReceivableSearch {

    private final ReceivableJpaRepository receivables;
    private final CustomerTransferJpaRepository transfers;
    private final EntityManager entityManager;

    ReceivableBookAdapter(ReceivableJpaRepository receivables, CustomerTransferJpaRepository transfers,
                          EntityManager entityManager) {
        this.receivables = receivables;
        this.transfers = transfers;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Receivable> findReceivable(UUID id) {
        return receivables.findById(id).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Optional<Receivable> findReceivableOfOrder(UUID orderId) {
        return receivables.findByOrderId(orderId).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public List<Receivable> lockUnpaid(UUID customerId) {
        return receivables.lockUnpaid(customerId).stream().map(ReceivableBookAdapter::toDomain).toList();
    }

    @Override
    public Receivable save(Receivable r) {
        ReceivableJpaEntity entity = receivables.findById(r.id()).orElseGet(() -> new ReceivableJpaEntity(r.id(),
                r.orderId(), r.customerId(), r.amount(), r.currency(), r.issuedAt(), r.dueDate()));
        entity.apply(r.paidAmount(), r.status(), r.settledAt());
        return toDomain(receivables.saveAndFlush(entity));
    }

    @Override
    public boolean referenceRecorded(String reference) {
        return transfers.existsByReference(reference.trim());
    }

    @Override
    public List<CustomerTransfer> lockWithCredit(UUID customerId) {
        return transfers.lockWithCredit(customerId).stream().map(ReceivableBookAdapter::toDomain).toList();
    }

    @Override
    public CustomerTransfer save(CustomerTransfer t) {
        CustomerTransferJpaEntity entity = transfers.findById(t.id()).orElseGet(() -> new CustomerTransferJpaEntity(
                t.id(), t.customerId(), t.reference(), t.amount(), t.currency(), t.receivedOn(), t.recordedBy(),
                t.recordedAt(), t.note()));
        entity.apply(t.unallocatedAmount());
        return toDomain(transfers.saveAndFlush(entity));
    }

    @Override
    public Allocation recordAllocation(UUID transferId, UUID receivableId, BigDecimal amount, Instant at, UUID by) {
        UUID id = Identifiers.newId();
        entityManager.createNativeQuery("""
                        INSERT INTO payment.transfer_allocation (id, transfer_id, receivable_id, amount, allocated_at,
                                                                 allocated_by)
                        VALUES (:id, :transfer, :receivable, :amount, :at, :by)""")
                .setParameter("id", id)
                .setParameter("transfer", transferId)
                .setParameter("receivable", receivableId)
                .setParameter("amount", amount)
                .setParameter("at", at)
                .setParameter("by", by)
                .executeUpdate();
        return new Allocation(id, transferId, receivableId, amount, at, by);
    }

    @Override
    public List<Allocation> allocationsOfReceivable(UUID receivableId) {
        return allocations("receivable_id", receivableId);
    }

    @Override
    public List<Allocation> allocationsOfTransfer(UUID transferId) {
        return allocations("transfer_id", transferId);
    }

    private List<Allocation> allocations(String column, UUID value) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, transfer_id, receivable_id, amount, allocated_at, allocated_by"
                                + " FROM payment.transfer_allocation WHERE " + column + " = :value"
                                + " ORDER BY allocated_at, id")
                .setParameter("value", value)
                .getResultList();
        return rows.stream().map(r -> new Allocation((UUID) r[0], (UUID) r[1], (UUID) r[2], (BigDecimal) r[3],
                instant(r[4]), (UUID) r[5])).toList();
    }

    @Override
    public List<UUID> markOverdue(LocalDate today) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        UPDATE payment.receivable SET status = 'OVERDUE', version = version + 1,
                                                      last_modified_at = NOW(), last_modified_by = 'overdue-job'
                         WHERE status IN ('OPEN', 'PARTIALLY_PAID') AND due_date < :today
                        RETURNING id""")
                .setParameter("today", today)
                .getResultList();
        return ids;
    }

    @Override
    public BigDecimal outstanding(UUID customerId) {
        Object sum = entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount - paid_amount), 0) FROM payment.receivable
                         WHERE customer_id = :customer AND status <> 'PAID'""")
                .setParameter("customer", customerId)
                .getSingleResult();
        return sum instanceof BigDecimal value ? value : new BigDecimal(sum.toString());
    }

    @Override
    public boolean hasOverdue(UUID customerId) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM payment.receivable WHERE customer_id = :customer AND status = 'OVERDUE'""")
                .setParameter("customer", customerId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public Page<Receivable> receivables(UUID customerId, ReceivableStatus status, LocalDate dueBefore,
                                        Pageable pageable) {
        Specification<ReceivableJpaEntity> spec = Specification
                .<ReceivableJpaEntity>where(Specs.eq("customerId", customerId))
                .and(Specs.eq("status", status))
                .and(dueBefore == null ? null : (root, query, cb) -> cb.lessThan(root.get("dueDate"), dueBefore));
        return receivables.findAll(spec, pageable).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Page<CustomerTransfer> transfers(UUID customerId, Pageable pageable) {
        Specification<CustomerTransferJpaEntity> spec = Specification
                .<CustomerTransferJpaEntity>where(Specs.eq("customerId", customerId));
        return transfers.findAll(spec, pageable).map(ReceivableBookAdapter::toDomain);
    }

    private static Receivable toDomain(ReceivableJpaEntity e) {
        return new Receivable(e.getId(), e.getOrderId(), e.getCustomerId(), e.getAmount(), e.getPaidAmount(),
                e.getCurrency(), e.getIssuedAt(), e.getDueDate(), e.getStatus(), e.getSettledAt(), e.getVersion());
    }

    private static CustomerTransfer toDomain(CustomerTransferJpaEntity e) {
        return new CustomerTransfer(e.getId(), e.getCustomerId(), e.getReference(), e.getAmount(),
                e.getUnallocatedAmount(), e.getCurrency(), e.getReceivedOn(), e.getRecordedBy(), e.getRecordedAt(),
                e.getNote(), e.getVersion());
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }
}
