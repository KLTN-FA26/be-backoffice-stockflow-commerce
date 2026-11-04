package com.stockflow.payment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.payment.internal.domain.Collector;
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
    public Optional<Receivable> lockReceivable(UUID id) {
        return receivables.lockById(id).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Optional<Receivable> findReceivableOfOrder(UUID orderId) {
        return receivables.findByOrderIdAndShipmentIdIsNull(orderId).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Optional<Receivable> findReceivableOfShipment(UUID orderId, UUID shipmentId) {
        return receivables.findByOrderIdAndShipmentId(orderId, shipmentId).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public List<Receivable> lockUnpaid(UUID customerId) {
        return receivables.lockUnpaid(customerId).stream().map(ReceivableBookAdapter::toDomain).toList();
    }

    @Override
    public Receivable save(Receivable r) {
        ReceivableJpaEntity entity = receivables.findById(r.id()).orElseGet(() -> new ReceivableJpaEntity(r.id(),
                r.orderId(), r.customerId(), r.amount(), r.currency(), r.issuedAt(), r.dueDate()).ofKind(r.kind())
                .ofShipment(r.shipmentId()).collectedBy(r.collector()).ofOrderNumber(r.orderNumber()));
        entity.apply(r.paidAmount(), r.status(), r.settledAt());
        return toDomain(receivables.saveAndFlush(entity));
    }

    @Override
    public boolean referenceRecorded(String reference) {
        String trimmed = reference.trim();
        // 15 BR-07 across the ledger and the payments recorded before it: one statement line, recorded once.
        return transfers.existsByReference(trimmed) || !entityManager.createNativeQuery(
                        "SELECT 1 FROM payment.payment WHERE provider_ref = :ref")
                .setParameter("ref", trimmed).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public Optional<CustomerTransfer> findTransfer(UUID id) {
        return transfers.findById(id).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Optional<CustomerTransfer> lockTransfer(UUID id) {
        return transfers.lockById(id).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public List<CustomerTransfer> lockWithCredit(UUID customerId) {
        return transfers.lockWithCredit(customerId).stream().map(ReceivableBookAdapter::toDomain).toList();
    }

    @Override
    public CustomerTransfer save(CustomerTransfer t) {
        CustomerTransferJpaEntity entity = transfers.findById(t.id()).orElseGet(() -> new CustomerTransferJpaEntity(
                t.id(), t.customerId(), t.reference(), t.amount(), t.currency(), t.receivedOn(), t.recordedBy(),
                t.recordedAt(), t.note()).viaChannel(t.channel(), t.carrierName()));
        entity.apply(t.unallocatedAmount(), t.refundedAmount());
        return toDomain(transfers.saveAndFlush(entity));
    }

    @Override
    public Allocation recordAllocation(UUID transferId, UUID orderId, UUID receivableId, BigDecimal amount, Instant at,
                                       UUID by) {
        UUID id = Identifiers.newId();
        insertAllocation(id, transferId, orderId, receivableId, amount, at, by, null, null);
        return new Allocation(id, transferId, orderId, receivableId, amount, at, by, null, null);
    }

    @Override
    public Allocation recordReversal(Allocation original, Instant at, UUID by, String note) {
        UUID id = Identifiers.newId();
        BigDecimal amount = original.amount().negate();
        // uk_transfer_allocation_reversal makes a second reversal of the same allocation fail here, whatever raced.
        insertAllocation(id, original.transferId(), original.orderId(), original.receivableId(), amount, at, by,
                original.id(), note);
        return new Allocation(id, original.transferId(), original.orderId(), original.receivableId(), amount, at, by,
                original.id(), note);
    }

    private void insertAllocation(UUID id, UUID transferId, UUID orderId, UUID receivableId, BigDecimal amount,
                                  Instant at, UUID by, UUID reverses, String note) {
        entityManager.createNativeQuery("""
                        INSERT INTO payment.transfer_allocation (id, transfer_id, order_id, receivable_id, amount,
                                                                 allocated_at, allocated_by, reverses_allocation_id,
                                                                 note)
                        VALUES (:id, :transfer, :order, :receivable, :amount, :at, :by, :reverses, :note)""")
                .setParameter("id", id)
                .setParameter("transfer", transferId)
                .setParameter("order", orderId)
                .setParameter("receivable", receivableId)
                .setParameter("amount", amount)
                .setParameter("at", at)
                .setParameter("by", by)
                .setParameter("reverses", reverses)
                .setParameter("note", note)
                .executeUpdate();
    }

    @Override
    public Optional<Allocation> findAllocation(UUID id) {
        return allocations("id", id).stream().findFirst();
    }

    @Override
    public List<Allocation> allocationsOfReceivable(UUID receivableId) {
        return allocations("receivable_id", receivableId);
    }

    @Override
    public List<Allocation> allocationsOfTransfer(UUID transferId) {
        return allocations("transfer_id", transferId);
    }

    @Override
    public List<Allocation> allocationsOfOrder(UUID orderId) {
        return allocations("order_id", orderId);
    }

    /** {@code column} is one of this class's constants, never input. */
    private List<Allocation> allocations(String column, UUID value) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, transfer_id, order_id, receivable_id, amount, allocated_at, allocated_by,"
                                + " reverses_allocation_id, note FROM payment.transfer_allocation WHERE " + column
                                + " = :value ORDER BY allocated_at, id")
                .setParameter("value", value)
                .getResultList();
        return rows.stream().map(r -> new Allocation((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3],
                (BigDecimal) r[4], instant(r[5]), (UUID) r[6], (UUID) r[7], (String) r[8])).toList();
    }

    @Override
    public boolean isReversed(UUID allocationId) {
        return exists("SELECT 1 FROM payment.transfer_allocation WHERE reverses_allocation_id = :id", allocationId);
    }

    @Override
    public boolean hasRefunds(UUID allocationId) {
        return exists("SELECT 1 FROM payment.refund WHERE allocation_id = :id AND status <> 'FAILED'", allocationId);
    }

    private boolean exists(String sql, UUID id) {
        return !entityManager.createNativeQuery(sql).setParameter("id", id).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public BigDecimal collectedFor(UUID orderId) {
        // payment.payment rows are the money recorded before the ledger (seed, legacy imports); REFUNDED ones were
        // captured first, and their refunds are subtracted with the others.
        return decimal(entityManager.createNativeQuery("""
                        SELECT COALESCE((SELECT SUM(a.amount) FROM payment.transfer_allocation a
                                          WHERE a.order_id = :order), 0)
                             + COALESCE((SELECT SUM(p.amount) FROM payment.payment p
                                          WHERE p.order_id = :order AND p.status IN ('CAPTURED', 'REFUNDED')), 0)
                             - COALESCE((SELECT SUM(r.amount) FROM payment.refund r
                                          WHERE r.order_id = :order AND r.status <> 'FAILED'), 0)""")
                .setParameter("order", orderId)
                .getSingleResult());
    }

    @Override
    public BigDecimal collectedOnReceivablesOf(UUID orderId) {
        return decimal(entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount), 0) FROM payment.transfer_allocation
                         WHERE order_id = :order AND receivable_id IS NOT NULL""")
                .setParameter("order", orderId)
                .getSingleResult());
    }

    @Override
    public BigDecimal outstandingReceivablesOf(UUID orderId) {
        return decimal(entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount - paid_amount), 0) FROM payment.receivable
                         WHERE order_id = :order AND status <> 'PAID'""")
                .setParameter("order", orderId)
                .getSingleResult());
    }

    @Override
    public List<RefundLine> refundsOfOrder(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, payment_id, allocation_id, amount, currency, status, source, created_at
                          FROM payment.refund WHERE order_id = :order ORDER BY created_at, id""")
                .setParameter("order", orderId)
                .getResultList();
        return rows.stream().map(r -> new RefundLine((UUID) r[0], (UUID) r[1], (UUID) r[2], decimal(r[3]),
                (String) r[4], (String) r[5], (String) r[6], instant(r[7]))).toList();
    }

    @Override
    public List<Receivable> receivablesOfOrder(UUID orderId) {
        return receivables.findByOrderIdOrderByDueDateAscIssuedAtAsc(orderId).stream()
                .map(ReceivableBookAdapter::toDomain).toList();
    }

    @Override
    public UUID recordCreditRefund(CustomerTransfer transfer, BigDecimal amount, String reason) {
        UUID id = Identifiers.newId();
        entityManager.createNativeQuery("""
                        INSERT INTO payment.refund (id, transfer_id, amount, currency, reason, status, created_at,
                                                    source)
                        VALUES (:id, :transfer, :amount, :currency, :reason, 'PENDING', NOW(), 'CUSTOMER_CREDIT')""")
                .setParameter("id", id)
                .setParameter("transfer", transfer.id())
                .setParameter("amount", amount)
                .setParameter("currency", transfer.currency())
                .setParameter("reason", reason)
                .executeUpdate();
        return id;
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
                         WHERE customer_id = :customer AND collector = 'CUSTOMER' AND status <> 'PAID'""")
                .setParameter("customer", customerId)
                .getSingleResult();
        return decimal(sum);
    }

    @Override
    public boolean hasOverdue(UUID customerId) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM payment.receivable
                         WHERE customer_id = :customer AND collector = 'CUSTOMER' AND status = 'OVERDUE'""")
                .setParameter("customer", customerId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public Overdue overdue(UUID customerId) {
        // The same rows hasOverdue looks at, so overdueAmount > 0 exactly when hasOverdue.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount - paid_amount), 0), MIN(due_date) FROM payment.receivable
                         WHERE customer_id = :customer AND collector = 'CUSTOMER' AND status = 'OVERDUE'""")
                .setParameter("customer", customerId)
                .getSingleResult();
        LocalDate oldest = switch (row[1]) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> LocalDate.parse(row[1].toString());
        };
        return new Overdue(decimal(row[0]), oldest);
    }

    @Override
    public Page<Receivable> receivables(UUID customerId, UUID orderId, ReceivableStatus status, LocalDate dueBefore,
                                        Collector collector, String kind, Pageable pageable) {
        Specification<ReceivableJpaEntity> spec = Specification
                .<ReceivableJpaEntity>where(Specs.eq("customerId", customerId))
                .and(Specs.eq("orderId", orderId))
                .and(Specs.eq("status", status))
                .and(Specs.eq("collector", collector))
                .and(Specs.eq("kind", kind))
                .and(dueBefore == null ? null : (root, query, cb) -> cb.lessThan(root.get("dueDate"), dueBefore));
        return receivables.findAll(spec, pageable).map(ReceivableBookAdapter::toDomain);
    }

    @Override
    public Page<CustomerTransfer> transfers(UUID customerId, boolean withCreditOnly, Pageable pageable) {
        Specification<CustomerTransferJpaEntity> spec = Specification
                .<CustomerTransferJpaEntity>where(Specs.eq("customerId", customerId))
                .and(!withCreditOnly ? null
                        : (root, query, cb) -> cb.greaterThan(root.get("unallocatedAmount"), BigDecimal.ZERO));
        return transfers.findAll(spec, pageable).map(ReceivableBookAdapter::toDomain);
    }

    private static Receivable toDomain(ReceivableJpaEntity e) {
        return new Receivable(e.getId(), e.getOrderId(), e.getCustomerId(), e.getAmount(), e.getPaidAmount(),
                e.getCurrency(), e.getIssuedAt(), e.getDueDate(), e.getStatus(), e.getSettledAt(), e.getVersion())
                .withKind(e.getKind()).forShipment(e.getShipmentId()).collectedBy(e.getCollector())
                .forOrderNumber(e.getOrderNumber());
    }

    private static CustomerTransfer toDomain(CustomerTransferJpaEntity e) {
        return new CustomerTransfer(e.getId(), e.getCustomerId(), e.getReference(), e.getAmount(),
                e.getUnallocatedAmount(), e.getRefundedAmount(), e.getCurrency(), e.getReceivedOn(), e.getRecordedBy(),
                e.getRecordedAt(), e.getNote(), e.getChannel(), e.getCarrierName(), e.getVersion());
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
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
