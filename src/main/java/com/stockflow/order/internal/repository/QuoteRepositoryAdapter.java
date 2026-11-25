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
import com.stockflow.order.internal.entity.QuoteFeeLineJpaEntity;
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
    public List<Quote> findAllById(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        // The lines are fetched with the quotes; their tiers and the fee lines load in batches (batch_size).
        return jpa.findAllByIdWithLines(ids).stream().map(QuoteRepositoryAdapter::toDomain).toList();
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
        entity.apply(quote.status(), quote.revision(), offer.validUntil(), offer.terms(), quote.sentAt(), quote.sentBy(), quote.respondedAt(),
                quote.respondedBy(), quote.customerFeedback(), quote.cancellationReason(), quote.convertedOrderId(),
                quote.convertedAt(), offer.requestedDeliveryDate());
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
            List<QuoteLineJpaEntity.Tier> tiers = line.tiers().stream()
                    .map(t -> new QuoteLineJpaEntity.Tier(t.minQuantity(), t.unitPrice().amount())).toList();
            java.math.BigDecimal price = line.acceptedUnitPrice() == null ? null : line.acceptedUnitPrice().amount();
            if (line.legacy()) {
                row.applyLegacy(line.lineNo(), line.sku().code(), line.designSnapshotId(), line.printSpec(),
                        line.quantity(), tiers, line.acceptedQuantity(), price);
            } else {
                row.applyCartons(line.lineNo(), line.sku().code(), line.uomConversionId(), line.unitsPerCarton(),
                        line.quantity(), tiers, line.acceptedQuantity(), price, line.taxClass(), line.taxRate());
            }
        }
        // 4B-3: the fee lines, written in place; a fee no longer on the draft goes (orphan removal).
        Map<UUID, QuoteFeeLineJpaEntity> existingFees = entity.getFees().stream()
                .collect(Collectors.toMap(QuoteFeeLineJpaEntity::getId, Function.identity()));
        boolean feesDropped = entity.getFees()
                .removeIf(row -> offer.fees().stream().noneMatch(fee -> fee.id().equals(row.getId())));
        if (feesDropped && !entity.isNew()) {
            // As for lines: a new plate fee of the same SKU would hit uk_quote_fee_line_plate before the old row goes.
            entityManager.flush();
        }
        for (com.stockflow.order.internal.domain.QuoteFee fee : offer.fees()) {
            QuoteFeeLineJpaEntity row = existingFees.get(fee.id());
            if (row == null) {
                row = new QuoteFeeLineJpaEntity(fee.id());
                entity.addFee(row);
            }
            row.apply(fee.kind(), fee.sku() == null ? null : fee.sku().code(),
                    fee.amount() == null ? null : fee.amount().amount(), fee.taxClass(), fee.taxRate());
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
                .<QuoteJpaEntity>allOf(Specs.eq("customerId", criteria.customerId()))
                .and(Specs.contains("quoteNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : statusAt(criteria.statuses(), criteria.now()))
                .and(criteria.seenByCustomerOnly()
                        ? Specification.<QuoteJpaEntity>anyOf(Specs.in("status", java.util.Arrays.stream(QuoteStatus.values())
                                        .filter(QuoteStatus::seenByCustomer).toList()),
                                // Cancelled after it was sent, or a revision being written after it was sent
                                // (Quote#seenByCustomer).
                                (root, query, cb) -> cb.and(root.get("status").in(QuoteStatus.CANCELLED,
                                        QuoteStatus.DRAFT), cb.isNotNull(root.get("sentAt"))))
                        : null);
        return jpa.findAll(spec, pageable).map(QuoteRepositoryAdapter::toRow);
    }

    /**
     * The statuses as read at {@code now} ({@code Quote.statusAt}): a SENT quote past its validity is EXPIRED - found
     * under EXPIRED and not under SENT - from the moment it passes, not only once {@code QuoteExpirySweeper} has written
     * it down, up to five minutes later. Every other status is the stored one.
     */
    private static Specification<QuoteJpaEntity> statusAt(List<QuoteStatus> wanted, Instant now) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> any = new java.util.ArrayList<>();
            List<QuoteStatus> stored = wanted.stream()
                    .filter(status -> status != QuoteStatus.SENT && status != QuoteStatus.EXPIRED).toList();
            if (!stored.isEmpty()) {
                any.add(root.get("status").in(stored));
            }
            if (wanted.contains(QuoteStatus.SENT)) {
                any.add(cb.and(cb.equal(root.get("status"), QuoteStatus.SENT),
                        cb.greaterThan(root.<Instant>get("validUntil"), now)));
            }
            if (wanted.contains(QuoteStatus.EXPIRED)) {
                any.add(cb.equal(root.get("status"), QuoteStatus.EXPIRED));
                any.add(cb.and(cb.equal(root.get("status"), QuoteStatus.SENT),
                        cb.lessThanOrEqualTo(root.<Instant>get("validUntil"), now)));
            }
            return cb.or(any.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    @Override
    public List<Transition> history(UUID quoteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT revision, from_status, to_status, reason, CAST(offer AS text), changed_at, changed_by
                          FROM ordering.quote_status_history WHERE quote_id = :quote ORDER BY changed_at, id""")
                .setParameter("quote", quoteId)
                .getResultList();
        return rows.stream().map(QuoteRepositoryAdapter::toTransition).toList();
    }

    @Override
    public Map<UUID, List<Transition>> histories(java.util.Collection<UUID> quoteIds) {
        if (quoteIds == null || quoteIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT revision, from_status, to_status, reason, CAST(offer AS text), changed_at, changed_by,
                               quote_id
                          FROM ordering.quote_status_history WHERE quote_id IN (:quotes)
                         ORDER BY quote_id, changed_at, id""")
                .setParameter("quotes", List.copyOf(quoteIds))
                .getResultList();
        Map<UUID, List<Transition>> histories = new java.util.LinkedHashMap<>();
        rows.forEach(r -> histories.computeIfAbsent((UUID) r[7], id -> new java.util.ArrayList<>()).add(toTransition(r)));
        return histories;
    }

    private static Transition toTransition(Object[] r) {
        return new Transition(((Number) r[0]).intValue(),
                r[1] == null ? null : QuoteStatus.valueOf((String) r[1]), QuoteStatus.valueOf((String) r[2]),
                (String) r[3], (String) r[4], toInstant(r[5]), (UUID) r[6]);
    }

    @Override
    public Optional<Row> findRow(UUID quoteId) {
        return jpa.findById(quoteId).map(QuoteRepositoryAdapter::toRow);
    }

    @Override
    public Map<UUID, Instant> lastSentValidUntil(java.util.Collection<UUID> quoteIds) {
        if (quoteIds == null || quoteIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT DISTINCT ON (quote_id) quote_id, offer ->> 'validUntil'
                          FROM ordering.quote_status_history
                         WHERE quote_id IN (:quotes) AND to_status = 'SENT' AND offer IS NOT NULL
                         ORDER BY quote_id, changed_at DESC, id DESC""")
                .setParameter("quotes", List.copyOf(quoteIds))
                .getResultList();
        Map<UUID, Instant> validity = new java.util.HashMap<>();
        rows.stream().filter(r -> r[1] != null).forEach(r -> validity.put((UUID) r[0], Instant.parse((String) r[1])));
        return validity;
    }

    @Override
    public Map<UUID, ConvertedOrderRef> convertedOrders(java.util.Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, order_number, status FROM ordering.customer_order WHERE id IN (:orders)""")
                .setParameter("orders", List.copyOf(new java.util.HashSet<>(orderIds)))
                .getResultList();
        Map<UUID, ConvertedOrderRef> orders = new java.util.HashMap<>();
        rows.forEach(r -> orders.put((UUID) r[0], new ConvertedOrderRef((UUID) r[0], (String) r[1], (String) r[2])));
        return orders;
    }

    @Override
    public Optional<UUID> quoteConvertedInto(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                        SELECT id FROM ordering.quote WHERE converted_order_id = :order
                         ORDER BY converted_at DESC NULLS LAST LIMIT 1""")
                .setParameter("order", orderId)
                .getResultList();
        return ids.stream().findFirst();
    }

    @Override
    public List<ByRequest> byRequestIds(java.util.Collection<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT request_id, id, quote_number, sent_at, status::text FROM ordering.quote
                         WHERE request_id IN (:requests)""")
                .setParameter("requests", List.copyOf(requestIds))
                .getResultList();
        // sent_at is cleared by nothing: a quote reopened for a revision keeps it (Quote#revisingSentQuote).
        return rows.stream().map(r -> new ByRequest((UUID) r[0], (UUID) r[1], (String) r[2],
                r[3] == null ? null : toInstant(r[3]), (String) r[4])).toList();
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
                e.getValidUntil(), e.getSentAt(), e.getConvertedOrderId(), e.getCreatedAt(),
                e.getCreatedBy(), e.getVersion());
    }

    static Quote toDomain(QuoteJpaEntity e) {
        List<QuoteLine> lines = e.getLines().stream().map(QuoteRepositoryAdapter::toDomain).toList();
        List<com.stockflow.order.internal.domain.QuoteFee> fees = e.getFees().stream()
                .sorted(java.util.Comparator.comparing(QuoteFeeLineJpaEntity::getKind)
                        .thenComparing(f -> f.getSku() == null ? "" : f.getSku()))
                .map(f -> new com.stockflow.order.internal.domain.QuoteFee(f.getId(), f.getKind(),
                        f.getSku() == null ? null : new Sku(f.getSku()),
                        f.getAmount() == null ? null : vnd(f.getAmount()), f.getTaxClass(), f.getTaxRate()))
                .toList();
        return Quote.restore(e.getId(), e.getQuoteNumber(), e.getRequestId(), e.getCustomerId(), e.getStatus(),
                e.getRevision(), new Quote.Offer(e.getValidUntil(), e.getTerms(), e.getRequestedDeliveryDate(), lines, fees),
                e.getSentAt(), e.getSentBy(), e.getRespondedAt(), e.getRespondedBy(), e.getCustomerFeedback(),
                e.getCancellationReason(), e.getConvertedOrderId(), e.getConvertedAt());
    }

    private static QuoteLine toDomain(QuoteLineJpaEntity l) {
        List<PriceTier> tiers = l.getTiers().stream()
                .map(t -> new PriceTier(t.getMinQuantity(), vnd(t.getUnitPrice()))).toList();
        Money price = l.getAcceptedUnitPrice() == null ? null : vnd(l.getAcceptedUnitPrice());
        return l.getSku() == null
                ? QuoteLine.legacy(l.getId(), l.getLineNo(), new Sku(l.getBlankSku()), l.getDesignSnapshotId(),
                        l.getPrintSpec(), l.getQuantity(), tiers, l.getAcceptedQuantity(), price)
                : QuoteLine.carton(l.getId(), l.getLineNo(), new Sku(l.getSku()), l.getUomConversionId(),
                        l.getUnitsPerCarton(), l.getCartons(), tiers, l.getAcceptedCartons(), price, l.getTaxClass(),
                        l.getTaxRate());
    }

    /** Whole dong, as stored with four decimal places: compare and display without trailing zeros. */
    private static Money vnd(java.math.BigDecimal amount) {
        return new Money(amount.stripTrailingZeros().setScale(0, java.math.RoundingMode.UNNECESSARY), Money.VND);
    }
}
