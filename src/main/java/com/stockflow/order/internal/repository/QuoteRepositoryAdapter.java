package com.stockflow.order.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.entity.QuoteJpaEntity;
import com.stockflow.order.internal.entity.QuoteLineJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class QuoteRepositoryAdapter implements QuoteRepository, QuoteSearch {

    /** Wait at most this long for another writer of the same quote, then answer 409 rather than hang. */
    private static final Map<String, Object> LOCK_TIMEOUT = Map.of("jakarta.persistence.lock.timeout", 3000);

    private final QuoteJpaRepository jpa;
    private final EntityManager entityManager;

    QuoteRepositoryAdapter(QuoteJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Quote> findById(UUID id) {
        return jpa.findByIdWithLines(id).map(QuoteRepositoryAdapter::toDomain);
    }

    /**
     * Takes the row lock <i>before</i> loading, as {@code StockItemRepositoryAdapter} does. Loading first and
     * locking with {@code refresh(PESSIMISTIC_WRITE)} fails under contention: the waiting caller holds the
     * version it read before the winner committed, and Hibernate's versioned lock refuses it with an
     * optimistic-lock error instead of letting it see the winner's result — two concurrent conversions
     * would answer one of them 409 rather than "already converted". The refresh after the lock only makes
     * sure a copy already in the persistence context is replaced by the committed row.
     */
    @Override
    public Optional<Quote> findByIdForUpdate(UUID id) {
        if (entityManager.createNativeQuery("SELECT id FROM ordering.quote WHERE id = :id FOR UPDATE")
                .setParameter("id", id).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithLines(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
            return toDomain(entity);
        });
    }

    @Override
    public Optional<Quote> findByRequestId(UUID requestId) {
        return jpa.findIdByRequestId(requestId).flatMap(this::findById);
    }

    @Override
    public void lockRequest(UUID requestId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "quote-request:" + requestId)
                .getSingleResult();
    }

    @Override
    public Quote save(Quote quote) {
        QuoteJpaEntity entity = jpa.findByIdWithLines(quote.id()).orElseGet(() ->
                new QuoteJpaEntity(quote.id(), quote.quoteNumber(), quote.requestId(), quote.customerId()));
        Quote.Offer offer = quote.offer();
        entity.apply(quote.status(), quote.revision(), offer.paymentTerm(), offer.depositPercent(),
                offer.validUntil(), offer.terms(), quote.sentAt(), quote.sentBy(), quote.respondedAt(),
                quote.respondedBy(), quote.customerFeedback(), quote.cancellationReason(), quote.convertedOrderId(),
                quote.convertedAt());
        Map<UUID, QuoteLineJpaEntity> existing = entity.getLines().stream()
                .collect(Collectors.toMap(QuoteLineJpaEntity::getId, Function.identity()));
        // A revised draft may drop lines: they go (orphan removal), the others are written in place.
        boolean dropped = entity.getLines()
                .removeIf(row -> offer.lines().stream().noneMatch(line -> line.id().equals(row.getId())));
        if (dropped && !entity.isNew()) {
            // Hibernate flushes inserts before deletes: without this, a new line taking a dropped
            // line's number would hit uk_quote_line_no before the old row is gone.
            entityManager.flush();
        }
        for (QuoteLine line : offer.lines()) {
            QuoteLineJpaEntity row = existing.get(line.id());
            if (row == null) {
                row = new QuoteLineJpaEntity(line.id());
                entity.addLine(row);
            }
            row.apply(line.lineNo(), line.blankSku().code(), line.designSnapshotId(), line.printSpec(),
                    line.quantity(),
                    line.tiers().stream().map(t -> new QuoteLineJpaEntity.Tier(t.minQuantity(), t.unitPrice().amount()))
                            .toList(),
                    line.acceptedQuantity(),
                    line.acceptedUnitPrice() == null ? null : line.acceptedUnitPrice().amount());
        }
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public void recordTransition(UUID quoteId, int revision, QuoteStatus from, QuoteStatus to, String reason,
                                 String offer, Instant at, UUID by) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.quote_status_history
                               (id, quote_id, revision, from_status, to_status, reason, offer, changed_at, changed_by)
                        VALUES (:id, :quote, :revision, :from, :to, :reason, CAST(:offer AS jsonb), :at, :by)""")
                .setParameter("id", com.stockflow.common.id.Identifiers.newId())
                .setParameter("quote", quoteId)
                .setParameter("revision", revision)
                .setParameter("from", from == null ? null : from.name())
                .setParameter("to", to.name())
                .setParameter("reason", reason)
                .setParameter("offer", offer)
                .setParameter("at", Timestamp.from(at))
                .setParameter("by", by)
                .executeUpdate();
    }

    @Override
    public List<UUID> sentAndExpiredBy(Instant now, int limit) {
        return jpa.findSentExpiredBy(now, PageRequest.of(0, limit));
    }

    /** Same counter table and upsert as TO-/ADJ-/GR-/SR- numbers; a rollback leaves a gap, never a duplicate. */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('QT', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "QT-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<QuoteJpaEntity> spec = Specification
                .<QuoteJpaEntity>where(Specs.eq("customerId", criteria.customerId()))
                .and(Specs.contains("quoteNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(criteria.seenByCustomerOnly()
                        ? Specs.in("status", java.util.Arrays.stream(QuoteStatus.values())
                                .filter(QuoteStatus::seenByCustomer).toList())
                        : null);
        return jpa.findAll(spec, pageable).map(QuoteRepositoryAdapter::toRow);
    }

    @Override
    public List<Transition> history(UUID quoteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT revision, from_status, to_status, reason, CAST(offer AS text), changed_at, changed_by
                          FROM ordering.quote_status_history WHERE quote_id = :quote ORDER BY changed_at, id""")
                .setParameter("quote", quoteId)
                .getResultList();
        return rows.stream().map(r -> new Transition(((Number) r[0]).intValue(),
                r[1] == null ? null : QuoteStatus.valueOf((String) r[1]), QuoteStatus.valueOf((String) r[2]),
                (String) r[3], (String) r[4], toInstant(r[5]), (UUID) r[6])).toList();
    }

    @Override
    public Optional<Row> findRow(UUID quoteId) {
        return jpa.findById(quoteId).map(QuoteRepositoryAdapter::toRow);
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant instant -> instant;
            case Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
        };
    }

    private static Row toRow(QuoteJpaEntity e) {
        return new Row(e.getId(), e.getQuoteNumber(), e.getCustomerId(), e.getStatus(), e.getRevision(),
                e.getPaymentTerm(), e.getValidUntil(), e.getSentAt(), e.getConvertedOrderId(), e.getCreatedAt(),
                e.getCreatedBy(), e.getVersion());
    }

    static Quote toDomain(QuoteJpaEntity e) {
        List<QuoteLine> lines = e.getLines().stream().map(l -> new QuoteLine(l.getId(), l.getLineNo(),
                new Sku(l.getBlankSku()), l.getDesignSnapshotId(), l.getPrintSpec(), l.getQuantity(),
                l.getTiers().stream().map(t -> new PriceTier(t.getMinQuantity(), vnd(t.getUnitPrice()))).toList(),
                l.getAcceptedQuantity(), l.getAcceptedUnitPrice() == null ? null : vnd(l.getAcceptedUnitPrice())))
                .toList();
        return Quote.restore(e.getId(), e.getQuoteNumber(), e.getRequestId(), e.getCustomerId(), e.getStatus(),
                e.getRevision(), new Quote.Offer(e.getPaymentTerm(), e.getDepositPercent(), e.getValidUntil(),
                        e.getTerms(), lines),
                e.getSentAt(), e.getSentBy(), e.getRespondedAt(), e.getRespondedBy(), e.getCustomerFeedback(),
                e.getCancellationReason(), e.getConvertedOrderId(), e.getConvertedAt());
    }

    /** Whole dong, as stored with four decimal places: compare and display without trailing zeros. */
    private static Money vnd(java.math.BigDecimal amount) {
        return new Money(amount.stripTrailingZeros().setScale(0, java.math.RoundingMode.UNNECESSARY), Money.VND);
    }
}
