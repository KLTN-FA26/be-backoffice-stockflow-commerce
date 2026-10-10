package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.entity.StockAdjustmentJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Repository
class StockAdjustmentRepositoryAdapter implements StockAdjustmentRepository, StockAdjustmentSearch {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockAdjustmentJpaRepository jpa;
    private final EntityManager entityManager;

    StockAdjustmentRepositoryAdapter(StockAdjustmentJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<StockAdjustment> findById(UUID id) {
        return jpa.findById(id).map(StockAdjustmentRepositoryAdapter::toDomain);
    }

    /**
     * One upsert on {@code platform.document_sequence}: the row lock it takes serialises two
     * requests on the same day, and a rolled-back request leaves a gap rather than a duplicate —
     * the right trade for a document number.
     */
    @Override
    public String nextNumber(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('ADJ', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "ADJ-%s-%04d".formatted(day.format(DAY), value.longValue());
    }

    @Override
    public StockAdjustment save(StockAdjustment adjustment) {
        StockAdjustmentJpaEntity entity = jpa.findById(adjustment.id()).orElseGet(() ->
                new StockAdjustmentJpaEntity(adjustment.id(), adjustment.number(), adjustment.location().code(),
                        adjustment.sku().code(), adjustment.lotNumber(), adjustment.quantityDelta(),
                        adjustment.reason(), adjustment.note(), adjustment.requestedBy()));
        entity.decide(adjustment.status(), adjustment.decidedBy(), adjustment.decidedAt(),
                adjustment.rejectionReason(), adjustment.postedAt(), adjustment.withdrawnAt());
        return toDomain(jpa.saveAndFlush(entity));
    }

    @Override
    public Page<StockAdjustment> search(Criteria criteria, Pageable pageable) {
        Specification<StockAdjustmentJpaEntity> spec = Specification
                .<StockAdjustmentJpaEntity>where(Specs.eq("status", criteria.status()))
                .and(Specs.eq("sku", upper(criteria.sku())))
                .and(Specs.eq("locationCode", upper(criteria.location())))
                .and(com.stockflow.common.security.WarehouseScope.locationsIn("locationCode"));
        return jpa.findAll(spec, pageable).map(StockAdjustmentRepositoryAdapter::toDomain);
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    static StockAdjustment toDomain(StockAdjustmentJpaEntity e) {
        return new StockAdjustment(e.getId(), e.getAdjustmentNumber(), new LocationId(e.getLocationCode()),
                new Sku(e.getSku()), e.getLotNumber(), e.getQuantityDelta(), e.getReasonCode(), e.getNote(),
                e.getRequestedBy(), e.getCreatedAt(), e.getStatus(), e.getDecidedBy(), e.getDecidedAt(),
                e.getRejectionReason(), e.getPostedAt(), e.getWithdrawnAt(), e.getVersion());
    }
}
