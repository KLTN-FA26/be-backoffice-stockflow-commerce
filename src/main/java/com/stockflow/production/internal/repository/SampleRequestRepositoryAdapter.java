package com.stockflow.production.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import com.stockflow.production.internal.entity.ProductionOrderJpaEntity;
import com.stockflow.production.internal.entity.SampleRequestJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class SampleRequestRepositoryAdapter implements SampleRequestRepository, SampleRequestSearch {

    /** Wait at most this long for another writer of the same round, then answer 409 rather than hang. */
    private static final Map<String, Object> LOCK_TIMEOUT = Map.of("jakarta.persistence.lock.timeout", 3000);
    /** A sample is asked again a handful of times at most; this only stops a corrupt chain looping. */
    private static final int MAX_ROUNDS = 100;

    private final SampleRequestJpaRepository jpa;
    private final EntityManager entityManager;

    SampleRequestRepositoryAdapter(SampleRequestJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    /**
     * Refreshes under the lock: returning the copy already in the persistence context would make the
     * lock decorative, deciding on state read before another writer committed.
     */
    @Override
    public Optional<SampleRequest> findByIdForUpdate(UUID id) {
        return jpa.findById(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE, LOCK_TIMEOUT);
            return toDomain(entity);
        });
    }

    @Override
    public Optional<SampleRequest> findById(UUID id) {
        return jpa.findById(id).map(SampleRequestRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<UUID> nextRoundOf(UUID id) {
        return jpa.findNextRoundId(id);
    }

    @Override
    public Optional<SampleRequest> latestApproved(UUID designSnapshotId, Sku blankSku) {
        return jpa.findApproved(designSnapshotId, blankSku.code(), PageRequest.of(0, 1)).stream().findFirst()
                .map(SampleRequestRepositoryAdapter::toDomain);
    }

    @Override
    public SampleRequest save(SampleRequest sample) {
        SampleRequestJpaEntity entity = jpa.findById(sample.id()).orElseGet(() -> new SampleRequestJpaEntity(
                sample.id(), sample.requestNumber(), sample.customerId(), sample.quoteId(), sample.previousRoundId(),
                sample.round()));
        SampleSpecification spec = sample.specification();
        entity.apply(spec.blankSku().code(), spec.designSnapshotId(), spec.quantity(), spec.dueDate(), sample.status(),
                sample.sentAt(), sample.respondedAt(), sample.respondedBy(), sample.customerFeedback(),
                sample.cancellationReason());
        return toDomain(jpa.saveAndFlush(entity));
    }

    /** Same counter table and upsert as TO-/ADJ-/GR- numbers; a rollback leaves a gap, never a duplicate. */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('SR', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "SR-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<SampleRequestJpaEntity> spec = Specification
                .<SampleRequestJpaEntity>allOf(Specs.eq("customerId", criteria.customerId()))
                .and(Specs.eq("designSnapshotId", criteria.designSnapshotId()))
                .and(Specs.contains("requestNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(madeIn(criteria));
        return jpa.findAll(spec, pageable).map(SampleRequestRepositoryAdapter::toRow);
    }

    /** A warehouse-bound user sees the rounds their warehouse makes: a draft is made nowhere yet. */
    private static Specification<SampleRequestJpaEntity> madeIn(Criteria criteria) {
        if (criteria.restrictToWarehouses() == null) {
            return null;
        }
        if (criteria.restrictToWarehouses().isEmpty()) {
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            Subquery<UUID> made = query.subquery(UUID.class);
            var order = made.from(ProductionOrderJpaEntity.class);
            made.select(order.get("sampleRequestId")).where(
                    cb.equal(order.get("type"), ProductionOrder.Type.SAMPLE),
                    order.get("warehouseId").in(criteria.restrictToWarehouses()));
            return root.get("id").in(made);
        };
    }

    @Override
    public Optional<Row> findRow(UUID id) {
        return jpa.findById(id).map(SampleRequestRepositoryAdapter::toRow);
    }

    @Override
    public List<Row> rounds(UUID id) {
        Deque<Row> chain = new ArrayDeque<>();
        Optional<SampleRequestJpaEntity> current = jpa.findById(id);
        while (current.isPresent() && chain.size() < MAX_ROUNDS) {
            chain.addFirst(toRow(current.get()));
            UUID previous = current.get().getPreviousRoundId();
            current = previous == null ? Optional.empty() : jpa.findById(previous);
        }
        Optional<UUID> next = jpa.findNextRoundId(id);
        while (next.isPresent() && chain.size() < MAX_ROUNDS) {
            Optional<SampleRequestJpaEntity> row = jpa.findById(next.get());
            if (row.isEmpty()) {
                break;
            }
            chain.addLast(toRow(row.get()));
            next = jpa.findNextRoundId(next.get());
        }
        return new ArrayList<>(chain);
    }

    static SampleRequest toDomain(SampleRequestJpaEntity e) {
        return SampleRequest.restore(e.getId(), e.getRequestNumber(), e.getCustomerId(), e.getQuoteId(),
                e.getPreviousRoundId(), e.getRoundNo(),
                new SampleSpecification(e.getDesignSnapshotId(), new Sku(e.getBlankSku()), e.getQuantity(),
                        e.getDueDate()),
                e.getStatus(), e.getSentAt(), e.getRespondedAt(), e.getRespondedBy(), e.getCustomerFeedback(),
                e.getCancellationReason());
    }

    private static Row toRow(SampleRequestJpaEntity e) {
        return new Row(e.getId(), e.getRequestNumber(), e.getCustomerId(), e.getQuoteId(), e.getPreviousRoundId(),
                e.getRoundNo(), e.getStatus(), e.getBlankSku(), e.getDesignSnapshotId(), e.getQuantity(),
                e.getDueDate(), e.getSentAt(), e.getRespondedAt(), e.getRespondedBy(), e.getCustomerFeedback(),
                e.getCancellationReason(), e.getCreatedAt(), e.getCreatedBy(), e.getLastModifiedAt(),
                e.getLastModifiedBy(), e.getVersion());
    }
}
