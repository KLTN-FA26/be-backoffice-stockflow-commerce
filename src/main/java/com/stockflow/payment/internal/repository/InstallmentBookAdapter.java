package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.entity.InstallmentJpaEntity;
import com.stockflow.payment.internal.entity.PaymentReceiptJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link InstallmentBook} and {@link InstallmentSearch} over {@code payment.installment}, {@code payment_receipt} and
 * {@code payment_receipt_line}.
 *
 * <p>A lock refreshes what it locked ({@code EntityManager.refresh} with {@code PESSIMISTIC_WRITE}): an installment
 * already in the persistence context would otherwise come back as it was read before the lock, and the lock would
 * guard nothing (CLAUDE.md §4, {@code findByIdForUpdate}). Receipt lines are plain rows written with their receipt and
 * never changed, so they are inserted and read with SQL rather than mapped.</p>
 */
@Repository
class InstallmentBookAdapter implements InstallmentBook, InstallmentSearch {

    private final InstallmentJpaRepository installments;
    private final PaymentReceiptJpaRepository receipts;
    private final EntityManager entityManager;

    InstallmentBookAdapter(InstallmentJpaRepository installments, PaymentReceiptJpaRepository receipts,
                           EntityManager entityManager) {
        this.installments = installments;
        this.receipts = receipts;
        this.entityManager = entityManager;
    }

    // ---- installments -----------------------------------------------------------------------------------------------

    @Override
    public Installment save(Installment i) {
        InstallmentJpaEntity entity = installments.findById(i.id()).orElseGet(() -> new InstallmentJpaEntity(i.id(),
                i.contractId(), i.contractInstallmentId(), i.orderId(), i.customerId(), i.contractNumber(),
                i.orderNumber(), i.seq(), i.name(), i.milestone(), i.deliveryBatchId(), i.deliveryBatchSeq(),
                i.amount(), i.currency(), i.dueDays()));
        entity.apply(i.paidAmount(), i.reducedAmount(), i.reachedAt(), i.dueDate(), i.status(), i.paidAt(),
                i.cancelledAt());
        return toDomain(installments.saveAndFlush(entity));
    }

    @Override
    public Optional<Installment> find(UUID id) {
        return installments.findById(id).map(InstallmentBookAdapter::toDomain);
    }

    @Override
    public List<Installment> ofOrder(UUID orderId) {
        return installments.findByOrderIdOrderBySeqAsc(orderId).stream().map(InstallmentBookAdapter::toDomain).toList();
    }

    @Override
    public List<Installment> lockOfOrder(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery(
                        "SELECT id FROM payment.installment WHERE order_id = :order ORDER BY id")
                .setParameter("order", orderId)
                .getResultList();
        return lock(ids);
    }

    @Override
    public List<Installment> lock(Collection<UUID> ids) {
        return ids.stream().filter(Objects::nonNull).distinct().sorted()
                .map(id -> entityManager.find(InstallmentJpaEntity.class, id))
                .filter(Objects::nonNull)
                .map(entity -> {
                    entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
                    return toDomain(entity);
                })
                .toList();
    }

    @Override
    public boolean opened(UUID contractId) {
        return installments.existsByContractId(contractId);
    }

    @Override
    public BigDecimal afterDeliveryUnpaid(UUID customerId) {
        return decimal(entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount - reduced_amount - paid_amount), 0) FROM payment.installment
                         WHERE customer_id = :customer AND milestone IN ('AFTER_DELIVERY', 'CANCELLATION_FEE')
                           AND status NOT IN ('PAID', 'CANCELLED')""")
                .setParameter("customer", customerId)
                .getSingleResult());
    }

    @Override
    public Overdue overdue(UUID customerId, LocalDate today) {
        // The same test as Installment.overdue and payment.installment_is_overdue: past its due day, still owing.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        SELECT COALESCE(SUM(amount - reduced_amount - paid_amount), 0), MIN(due_date)
                          FROM payment.installment
                         WHERE customer_id = :customer AND due_date < :today
                           AND status NOT IN ('PAID', 'CANCELLED')""")
                .setParameter("customer", customerId)
                .setParameter("today", today)
                .getSingleResult();
        return new Overdue(decimal(row[0]), localDate(row[1]));
    }

    // ---- receipts ---------------------------------------------------------------------------------------------------

    @Override
    public PaymentReceipt record(PaymentReceipt r) {
        PaymentReceiptJpaEntity entity = receipts.saveAndFlush(new PaymentReceiptJpaEntity(r.id(), r.customerId(),
                r.reference(), r.receivedOn(), r.amount(), r.currency(), r.note(), r.recordedBy(), r.recordedAt()));
        for (PaymentReceipt.Line line : r.lines()) {
            entityManager.createNativeQuery("""
                            INSERT INTO payment.payment_receipt_line (id, receipt_id, installment_id, amount)
                            VALUES (:id, :receipt, :installment, :amount)""")
                    .setParameter("id", line.id())
                    .setParameter("receipt", r.id())
                    .setParameter("installment", line.installmentId())
                    .setParameter("amount", line.amount())
                    .executeUpdate();
        }
        return toDomain(entity, r.lines());
    }

    @Override
    public PaymentReceipt saveVoided(PaymentReceipt r) {
        PaymentReceiptJpaEntity entity = receipts.findById(r.id())
                .orElseThrow(() -> new IllegalStateException("No receipt " + r.id()));
        entity.voided(r.voidedBy(), r.voidedAt(), r.voidReason());
        return toDomain(receipts.saveAndFlush(entity), r.lines());
    }

    @Override
    public Optional<PaymentReceipt> findReceipt(UUID id) {
        return receipts.findById(id).map(entity -> toDomain(entity, linesOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public Optional<PaymentReceipt> lockReceipt(UUID id) {
        PaymentReceiptJpaEntity entity = entityManager.find(PaymentReceiptJpaEntity.class, id);
        if (entity == null) {
            return Optional.empty();
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return Optional.of(toDomain(entity, linesOf(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public boolean referenceInUse(String reference) {
        // Receipts only: the money ledger's transfers and payments, which this also looked through, are gone (4B-11).
        return receipts.existsByReferenceAndStatus(reference.strip(), PaymentReceiptStatus.RECORDED);
    }

    @Override
    public List<ReceiptOfInstallment> receiptsOf(UUID installmentId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT r.id, r.reference, r.received_on, l.amount, r.status, r.recorded_at
                          FROM payment.payment_receipt_line l
                          JOIN payment.payment_receipt r ON r.id = l.receipt_id
                         WHERE l.installment_id = :installment
                         ORDER BY r.recorded_at, r.id""")
                .setParameter("installment", installmentId)
                .getResultList();
        return rows.stream().map(row -> new ReceiptOfInstallment((UUID) row[0], (String) row[1], localDate(row[2]),
                decimal(row[3]), PaymentReceiptStatus.valueOf((String) row[4]), instant(row[5]))).toList();
    }

    // ---- search -----------------------------------------------------------------------------------------------------

    @Override
    public Page<Installment> installments(InstallmentFilter filter, Pageable pageable) {
        Specification<InstallmentJpaEntity> spec = Specification
                .<InstallmentJpaEntity>where(Specs.eq("customerId", filter.customerId()))
                .and(Specs.eq("orderId", filter.orderId()))
                .and(Specs.eq("status", filter.status()))
                .and(Specs.eq("milestone", filter.milestone()))
                .and(filter.dueBefore() == null ? null
                        : (root, query, cb) -> cb.lessThan(root.get("dueDate"), filter.dueBefore()))
                .and(filter.reached() == null ? null : (root, query, cb) -> filter.reached()
                        ? cb.isNotNull(root.get("reachedAt")) : cb.isNull(root.get("reachedAt")))
                .and(filter.overdueOn() == null ? null : overdue(filter.overdueOn(), filter.overdue()));
        return installments.findAll(spec, pageable).map(InstallmentBookAdapter::toDomain);
    }

    /**
     * Overdue on {@code day}, or not: past its due day and still owing - the test of {@code Installment.overdue}, as a
     * predicate, since overdue is no status to filter by.
     */
    private static Specification<InstallmentJpaEntity> overdue(LocalDate day, boolean wanted) {
        return (root, query, cb) -> {
            var settled = root.get("status").in(InstallmentStatus.PAID, InstallmentStatus.CANCELLED);
            // Not overdue is spelt out rather than negated: an installment with no due day compares as NULL, and
            // NOT NULL would leave it out of both readings.
            return wanted ? cb.and(cb.lessThan(root.get("dueDate"), day), cb.not(settled))
                    : cb.or(cb.isNull(root.get("dueDate")), cb.greaterThanOrEqualTo(root.get("dueDate"), day), settled);
        };
    }

    @Override
    public Page<PaymentReceipt> receipts(UUID customerId, PaymentReceiptStatus status, String reference,
                                         Pageable pageable) {
        Specification<PaymentReceiptJpaEntity> spec = Specification
                .<PaymentReceiptJpaEntity>where(Specs.eq("customerId", customerId))
                .and(Specs.eq("status", status))
                .and(Specs.eq("reference", reference == null || reference.isBlank() ? null : reference.strip()));
        Page<PaymentReceiptJpaEntity> page = receipts.findAll(spec, pageable);
        // One query for the lines of the whole page.
        Map<UUID, List<PaymentReceipt.Line>> lines = linesOf(page.map(PaymentReceiptJpaEntity::getId).getContent());
        return page.map(entity -> toDomain(entity, lines.getOrDefault(entity.getId(), List.of())));
    }

    private Map<UUID, List<PaymentReceipt.Line>> linesOf(List<UUID> receiptIds) {
        if (receiptIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, receipt_id, installment_id, amount FROM payment.payment_receipt_line
                         WHERE receipt_id IN (:receipts) ORDER BY created_at, id""")
                .setParameter("receipts", receiptIds)
                .getResultList();
        return rows.stream().collect(Collectors.groupingBy(row -> (UUID) row[1], Collectors.mapping(
                row -> new PaymentReceipt.Line((UUID) row[0], (UUID) row[2], decimal(row[3])), Collectors.toList())));
    }

    // ---- mapping ----------------------------------------------------------------------------------------------------

    private static Installment toDomain(InstallmentJpaEntity e) {
        return new Installment(e.getId(), e.getContractId(), e.getContractInstallmentId(), e.getOrderId(),
                e.getCustomerId(), e.getContractNumber(), e.getOrderNumber(), e.getSeq(), e.getName(),
                e.getMilestone(), e.getDeliveryBatchId(), e.getDeliveryBatchSeq(), e.getAmount(), e.getPaidAmount(),
                e.getReducedAmount(), e.getCurrency(), e.getDueDays(), e.getReachedAt(), e.getDueDate(),
                e.getStatus(), e.getPaidAt(), e.getCancelledAt(), e.getVersion());
    }

    private static PaymentReceipt toDomain(PaymentReceiptJpaEntity e, List<PaymentReceipt.Line> lines) {
        return new PaymentReceipt(e.getId(), e.getCustomerId(), e.getReference(), e.getReceivedOn(), e.getAmount(),
                e.getCurrency(), e.getNote(), e.getStatus(), e.getRecordedBy(), e.getRecordedAt(), e.getVoidedBy(),
                e.getVoidedAt(), e.getVoidReason(), lines, e.getVersion());
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    private static LocalDate localDate(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case java.sql.Date d -> d.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> Instant.parse(value.toString());
        };
    }
}
