package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.BankTransaction;
import com.stockflow.payment.internal.domain.BankTransactionLog;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import com.stockflow.payment.internal.domain.BankTransfer;
import com.stockflow.payment.internal.entity.BankTransactionJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BankTransactionLog} over {@code payment.bank_transaction}.
 *
 * <p>A transfer is inserted with SQL and {@code ON CONFLICT DO NOTHING}: SePay sends one call again when it saw no
 * answer, and two webhooks pointed at one URL send it twice at once, so a repeat must be told apart by the unique
 * indexes rather than refused with an exception that would roll the caller back. A lock refreshes what it locked, or an
 * entity already in the persistence context would come back as it was read before the lock.</p>
 */
@Repository
class BankTransactionLogAdapter implements BankTransactionLog {

    private final BankTransactionJpaRepository transactions;
    private final EntityManager entityManager;

    BankTransactionLogAdapter(BankTransactionJpaRepository transactions, EntityManager entityManager) {
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Override
    public boolean keep(BankTransaction t) {
        BankTransfer transfer = t.transfer();
        int inserted = entityManager.createNativeQuery("""
                INSERT INTO payment.bank_transaction (id, provider, provider_transaction_id, source, dedupe_key,
                    account_number, amount, content, bank_reference, payment_code, transaction_at, received_at, raw,
                    status, version, created_at, created_by)
                VALUES (:id, :provider, :providerId, :source, :dedupeKey, :account, :amount, :content, :reference,
                    :code, :transactionAt, :receivedAt, CAST(:raw AS jsonb), 'RECEIVED', 0, :receivedAt, 'bank-feed')
                ON CONFLICT DO NOTHING""")
                .setParameter("id", t.id())
                .setParameter("provider", t.provider().name())
                .setParameter("providerId", transfer.providerTransactionId())
                .setParameter("source", t.source().name())
                .setParameter("dedupeKey", transfer.dedupeKey())
                .setParameter("account", transfer.accountNumber())
                .setParameter("amount", transfer.amount())
                .setParameter("content", transfer.content())
                .setParameter("reference", transfer.bankReference())
                .setParameter("code", transfer.paymentCode())
                .setParameter("transactionAt", Timestamp.from(transfer.transactionAt()))
                .setParameter("receivedAt", Timestamp.from(t.receivedAt()))
                .setParameter("raw", transfer.raw())
                .executeUpdate();
        return inserted == 1;
    }

    @Override
    public Optional<BankTransaction> findSame(BankTransaction.Provider provider, String providerTransactionId,
                                              String dedupeKey) {
        return transactions.findByProviderAndProviderTransactionId(provider.name(), providerTransactionId)
                .or(() -> transactions.findByProviderAndDedupeKey(provider.name(), dedupeKey))
                .map(BankTransactionLogAdapter::toDomain);
    }

    @Override
    public Optional<BankTransaction> find(UUID id) {
        return transactions.findById(id).map(BankTransactionLogAdapter::toDomain);
    }

    @Override
    public Optional<BankTransaction> lock(UUID id) {
        return transactions.findById(id).map(this::locked);
    }

    @Override
    public Optional<BankTransaction> lockByReceipt(UUID receiptId) {
        return transactions.findByReceiptId(receiptId).map(this::locked);
    }

    private BankTransaction locked(BankTransactionJpaEntity entity) {
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return toDomain(entity);
    }

    @Override
    public BankTransaction save(BankTransaction t) {
        BankTransactionJpaEntity entity = transactions.findById(t.id())
                .orElseThrow(() -> new IllegalStateException("Bank transaction %s was never kept".formatted(t.id())));
        entity.decide(t.status(), t.reviewReason() == null ? null : t.reviewReason().name(), t.reviewNote(),
                t.customerId(), t.installmentId(), t.receiptId(), t.decidedAt(), t.reviewedBy(), t.reviewedAt(),
                t.ignoreReason());
        return toDomain(transactions.saveAndFlush(entity));
    }

    @Override
    public Optional<String> raw(UUID id) {
        return transactions.findById(id).map(BankTransactionJpaEntity::getRaw);
    }

    @Override
    public List<UUID> stranded(Instant before) {
        return transactions.findByStatusAndReceivedAtBeforeOrderByReceivedAtAsc(BankTransactionStatus.RECEIVED, before)
                .stream().map(BankTransactionJpaEntity::getId).toList();
    }

    @Override
    public Page search(Collection<BankTransactionStatus> statuses, Instant from, Instant to,
                       java.math.BigDecimal amount, int page, int size) {
        Specification<BankTransactionJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) {
                all.add(root.get("status").in(statuses));
            }
            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("transactionAt"), from));
            }
            if (to != null) {
                all.add(cb.lessThan(root.get("transactionAt"), to));
            }
            if (amount != null) {
                // numeric = numeric: 660000 finds the transfer kept as 660000.0000.
                all.add(cb.equal(root.get("amount"), amount));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        org.springframework.data.domain.Page<BankTransactionJpaEntity> found = transactions.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "transactionAt", "id")));
        return new Page(found.getContent().stream().map(BankTransactionLogAdapter::toDomain).toList(),
                found.getTotalElements());
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UUID> installmentsNamed(Collection<String> contractNumbers, int seq) {
        if (contractNumbers.isEmpty()) {
            return List.of();
        }
        return entityManager.createNativeQuery("""
                SELECT id FROM payment.installment
                 WHERE contract_number IN (:numbers) AND seq = :seq
                 ORDER BY id""", UUID.class)
                .setParameter("numbers", contractNumbers)
                .setParameter("seq", seq)
                .getResultList();
    }

    private static BankTransaction toDomain(BankTransactionJpaEntity e) {
        BankTransfer transfer = new BankTransfer(e.getProviderTransactionId(), e.getAccountNumber(), e.getAmount(),
                e.getContent(), e.getBankReference(), e.getPaymentCode(), e.getTransactionAt(), e.getRaw());
        return new BankTransaction(e.getId(), BankTransaction.Provider.valueOf(e.getProvider()),
                BankTransaction.Source.valueOf(e.getSource()), transfer, e.getReceivedAt(), e.getStatus(),
                e.getReviewReason() == null ? null : BankTransaction.ReviewReason.valueOf(e.getReviewReason()),
                e.getReviewNote(), e.getCustomerId(), e.getInstallmentId(), e.getReceiptId(), e.getDecidedAt(),
                e.getReviewedBy(), e.getReviewedAt(), e.getIgnoreReason(), e.getVersion());
    }
}
