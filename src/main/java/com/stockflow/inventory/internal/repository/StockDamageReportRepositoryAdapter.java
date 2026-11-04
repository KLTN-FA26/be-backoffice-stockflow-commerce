package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.DamageDisposition;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import com.stockflow.inventory.internal.domain.StockDamageReportRepository;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.entity.StockDamageDispositionJpaEntity;
import com.stockflow.inventory.internal.entity.StockDamageSupplierShipmentJpaEntity;
import com.stockflow.inventory.internal.entity.StockDamagePhotoJpaEntity;
import com.stockflow.inventory.internal.entity.StockDamageReportJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The damage-report port and its search on Spring Data. The only class that speaks both the report's language and
 * the tables'.
 *
 * <p>A save adds the decisions the aggregate gained and nothing else. Photos and decisions are append-only in the
 * database, so the aggregate's children are never written twice, never replaced, never deleted.</p>
 */
@Repository
class StockDamageReportRepositoryAdapter implements StockDamageReportRepository, StockDamageReportSearch {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final StockDamageReportJpaRepository jpa;
    private final EntityManager entityManager;

    StockDamageReportRepositoryAdapter(StockDamageReportJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<StockDamageReport> findById(UUID id) {
        return jpa.findById(id).map(StockDamageReportRepositoryAdapter::toDomain);
    }

    /**
     * A scalar {@code SELECT ... FOR UPDATE} takes the lock, then the row is re-read under it: a copy already in the
     * persistence context would otherwise be handed back with the decisions it had before the lock (the trap
     * {@code StockItemRepositoryAdapter#findByIdForUpdate} documents).
     */
    @Override
    public Optional<StockDamageReport> findByIdForUpdate(UUID id) {
        if (entityManager.createNativeQuery("select id from inventory.stock_damage_report where id = :id for update")
                .setParameter("id", id).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findById(id).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    @Override
    public Optional<StockDamageReport> findByRequestId(UUID requestId) {
        return jpa.findByRequestId(requestId).map(StockDamageReportRepositoryAdapter::toDomain);
    }

    @Override
    public boolean isImpairedAllocation(UUID reservationId) {
        return jpa.existsByImpairedReservationId(reservationId);
    }

    @Override
    public int damagedAfterPick(UUID reservationId) {
        return Math.toIntExact(jpa.damagedAfterPick(reservationId));
    }

    @Override
    public String nextReportNumber(LocalDate day) {
        return "DMG-%s-%04d".formatted(day.format(DAY), next("DMG", day));
    }

    @Override
    public String nextWriteOffNumber(LocalDate day) {
        return "WO-%s-%04d".formatted(day.format(DAY), next("WO", day));
    }

    /**
     * One upsert on {@code platform.document_sequence}, as stock adjustments number theirs: the row lock serialises
     * two requests of one day, and a rolled-back request leaves a gap rather than a duplicate.
     */
    private long next(String documentType, LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES (:type, :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("type", documentType)
                .setParameter("day", day)
                .getSingleResult();
        return value.longValue();
    }

    /**
     * A new report is inserted with its photos, at once - so a duplicate request id or a reference the database
     * refuses surfaces here, inside the caller's checks, not at commit. An existing one gains its new decisions only.
     */
    @Override
    public StockDamageReport save(StockDamageReport report) {
        Optional<StockDamageReportJpaEntity> existing = jpa.findById(report.id());
        if (existing.isEmpty()) {
            StockDamageReportJpaEntity created = toNewEntity(report);
            report.dispositions().forEach(d -> created.addDisposition(toEntity(d)));
            return toDomain(jpa.saveAndFlush(created));
        }
        StockDamageReportJpaEntity entity = existing.get();
        Set<UUID> stored = entity.getDispositions().stream().map(StockDamageDispositionJpaEntity::getId)
                .collect(Collectors.toSet());
        report.dispositions().stream().filter(d -> !stored.contains(d.id()))
                .forEach(d -> entity.addDisposition(toEntity(d)));
        // Decisions first: a hand-over names its decision (fk_stock_damage_supplier_shipment_disposition).
        jpa.flush();
        Set<UUID> shipped = entity.getShipments().stream().map(StockDamageSupplierShipmentJpaEntity::getId)
                .collect(Collectors.toSet());
        report.shipments().stream().filter(s -> !shipped.contains(s.id()))
                .forEach(s -> entity.addShipment(new StockDamageSupplierShipmentJpaEntity(s.id(), s.dispositionId(),
                        s.requestId(), s.quantity().value(), s.deliveryNote(), s.note(), s.shippedBy(),
                        s.shippedAt(), s.refused())));
        jpa.flush();
        return toDomain(entity);
    }

    // ------------------------------------------------------------------ search

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<StockDamageReportJpaEntity> spec = Specification.allOf(
                Specs.eq("warehouseId", criteria.warehouseId()),
                Specs.eq("sku", criteria.sku() == null || criteria.sku().isBlank() ? null
                        : criteria.sku().trim().toUpperCase(java.util.Locale.ROOT)),
                Specs.eq("origin", criteria.origin()),
                open(criteria.open()),
                awaitingSupplier(criteria.awaitingSupplier()),
                WarehouseScope.warehousesIn("warehouseId"));
        Page<StockDamageReportJpaEntity> page = jpa.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(StockDamageReportJpaEntity::getId).toList();
        Map<UUID, Long> decided = ids.isEmpty() ? Map.of() : jpa.decidedOf(ids).stream().collect(Collectors.toMap(
                StockDamageReportJpaRepository.DecidedUnits::getReportId,
                StockDamageReportJpaRepository.DecidedUnits::getDecided));
        Map<UUID, Long> refused = ids.isEmpty() ? Map.of() : jpa.refusedOf(ids).stream().collect(Collectors.toMap(
                StockDamageReportJpaRepository.DecidedUnits::getReportId,
                StockDamageReportJpaRepository.DecidedUnits::getDecided));
        Map<UUID, Long> awaiting = ids.isEmpty() ? Map.of() : jpa.awaitingSupplierOf(ids).stream().collect(
                Collectors.toMap(StockDamageReportJpaRepository.DecidedUnits::getReportId,
                        StockDamageReportJpaRepository.DecidedUnits::getDecided));
        return page.map(e -> new Row(e.getId(), e.getReportNumber(), e.getOrigin(), e.getWarehouseId(), e.getSku(),
                e.getLotNumber(), e.getSourceLocationCode(), e.getDamageLocationCode(), e.getQuantity(),
                Math.toIntExact(decided.getOrDefault(e.getId(), 0L) - refused.getOrDefault(e.getId(), 0L)),
                e.getReasonCode(), e.getOrderId(), e.getReportedBy(), e.getReportedAt(),
                Math.toIntExact(Math.max(0L, awaiting.getOrDefault(e.getId(), 0L)))));
    }

    /** Open: some units still wait for a decision. Every report when the caller does not filter. */
    private static Specification<StockDamageReportJpaEntity> open(Boolean open) {
        if (open == null) {
            return Specs.all();
        }
        return (root, query, cb) -> {
            Subquery<Long> decided = query.subquery(Long.class);
            Root<StockDamageDispositionJpaEntity> disposition = decided.from(StockDamageDispositionJpaEntity.class);
            decided.select(cb.coalesce(cb.sumAsLong(disposition.get("quantity")), 0L))
                    .where(cb.equal(disposition.get("report"), root));
            // Units a supplier refused wait for a decision again.
            Subquery<Long> refused = query.subquery(Long.class);
            Root<StockDamageSupplierShipmentJpaEntity> refusal = refused.from(StockDamageSupplierShipmentJpaEntity.class);
            refused.select(cb.coalesce(cb.sumAsLong(refusal.get("quantity")), 0L))
                    .where(cb.equal(refusal.get("report"), root), cb.equal(refusal.get("outcome"), "REFUSED"));
            var waiting = cb.sum(cb.diff(root.<Integer>get("quantity"), decided), refused);
            return open ? cb.gt(waiting, 0L) : cb.le(waiting, 0L);
        };
    }

    /** Units decided for the supplier and neither handed over nor refused yet: some, or none. */
    private static Specification<StockDamageReportJpaEntity> awaitingSupplier(Boolean awaiting) {
        if (awaiting == null) {
            return Specs.all();
        }
        return (root, query, cb) -> {
            Subquery<Long> sent = query.subquery(Long.class);
            Root<StockDamageDispositionJpaEntity> disposition = sent.from(StockDamageDispositionJpaEntity.class);
            sent.select(cb.coalesce(cb.sumAsLong(disposition.get("quantity")), 0L))
                    .where(cb.equal(disposition.get("report"), root),
                            cb.equal(disposition.get("type"), DamageDisposition.RETURN_TO_SUPPLIER));
            Subquery<Long> handled = query.subquery(Long.class);
            Root<StockDamageSupplierShipmentJpaEntity> shipment = handled.from(StockDamageSupplierShipmentJpaEntity.class);
            handled.select(cb.coalesce(cb.sumAsLong(shipment.get("quantity")), 0L))
                    .where(cb.equal(shipment.get("report"), root));
            return awaiting ? cb.gt(sent, handled) : cb.le(sent, handled);
        };
    }

    // ------------------------------------------------------------------ mapping

    /** Rehydrated through the aggregate's constructor, so every rule is checked again on load. */
    static StockDamageReport toDomain(StockDamageReportJpaEntity e) {
        StockDamageReport.DamagedGoods goods = new StockDamageReport.DamagedGoods(
                new StockItemId(e.getSourceStockItemId()), new Sku(e.getSku()), e.getLotNumber(), e.getSerialNumber(),
                e.getReceivedAt(), e.getLotId(), new LocationId(e.getSourceLocationCode()),
                e.getSourceStockPackageId() == null ? null : new StockPackageId(e.getSourceStockPackageId()),
                e.getWarehouseId());
        StockDamageReport.PickContext pick = e.getOrigin() != StockDamageReport.Origin.STOCK_CHECK
                ? new StockDamageReport.PickContext(e.getOrderId(), e.getPickLineId(), e.getReservationId(),
                        e.getImpairedReservationId())
                : null;
        List<StockDamageReport.Photo> photos = e.getPhotos().stream()
                .map(p -> new StockDamageReport.Photo(p.getId(), p.getStorageKey(), p.getOriginalName(),
                        p.getContentType(), p.getSizeBytes(), p.getChecksum()))
                .toList();
        List<StockDamageReport.Disposition> dispositions = e.getDispositions().stream()
                .map(d -> new StockDamageReport.Disposition(d.getId(), d.getRequestId(), d.getType(),
                        Quantity.of(d.getQuantity()), d.getNote(), d.getRecordNumber(), d.getStockAdjustmentId(),
                        d.getRestoreLocationCode() == null ? null : new LocationId(d.getRestoreLocationCode()),
                        d.getRestoredStockItemId() == null ? null : new StockItemId(d.getRestoredStockItemId()),
                        d.getSupplierId(), d.getSupplierName(), d.getDecidedBy(), d.getDecidedAt()))
                .toList();
        List<StockDamageReport.SupplierShipment> shipments = e.getShipments().stream()
                .map(s -> new StockDamageReport.SupplierShipment(s.getId(), s.getRequestId(), s.getDispositionId(),
                        Quantity.of(s.getQuantity()), s.getDeliveryNote(), s.getNote(), s.getShippedBy(),
                        s.getShippedAt(), s.isRefused()))
                .toList();
        return new StockDamageReport(e.getId(), e.getReportNumber(), e.getRequestId(), e.getOrigin(), goods,
                new StockItemId(e.getDamagedStockItemId()), new LocationId(e.getDamageLocationCode()),
                Quantity.of(e.getQuantity()), e.getReasonCode(), e.getNote(), photos, pick, e.getReportedBy(),
                e.getReportedAt(), dispositions, shipments, e.getVersion());
    }

    private static StockDamageReportJpaEntity toNewEntity(StockDamageReport report) {
        StockDamageReport.DamagedGoods goods = report.goods();
        StockDamageReportJpaEntity entity = new StockDamageReportJpaEntity(report.id(), report.number(),
                report.requestId(), report.origin(), goods.warehouseId(), goods.sku().code(), goods.lotNumber(),
                goods.serialNumber(), goods.receivedAt(), goods.lotId(), goods.sourceStockItem().value(),
                goods.sourceLocation().code(),
                goods.sourceStockPackage() == null ? null : goods.sourceStockPackage().value(),
                report.damagedStockItem().value(), report.damageLocation().code(), report.quantity().value(),
                report.reason(), report.note(), report.reportedBy(), report.reportedAt());
        StockDamageReport.PickContext pick = report.pick();
        if (pick != null) {
            entity.pickedFor(pick.orderId(), pick.pickLineId(), pick.reservationId(), pick.impairedReservationId());
        }
        int order = 1;
        for (StockDamageReport.Photo photo : report.photos()) {
            entity.addPhoto(new StockDamagePhotoJpaEntity(photo.id(), photo.storageKey(), photo.originalName(),
                    photo.contentType(), photo.sizeBytes(), photo.checksum(), order++));
        }
        return entity;
    }

    private static StockDamageDispositionJpaEntity toEntity(StockDamageReport.Disposition d) {
        return new StockDamageDispositionJpaEntity(d.id(), d.requestId(), d.type(), d.quantity().value(), d.note(),
                d.recordNumber(), d.adjustmentId(),
                d.restoreLocation() == null ? null : d.restoreLocation().code(),
                d.restoredStockItem() == null ? null : d.restoredStockItem().value(), d.supplierId(),
                d.supplierName(), d.decidedBy(), d.decidedAt());
    }
}
