package com.stockflow.procurement.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReturnReceipt;
import com.stockflow.procurement.internal.domain.ReturnReceiptRepository;
import com.stockflow.procurement.internal.entity.ReturnReceiptCartonJpaEntity;
import com.stockflow.procurement.internal.entity.ReturnReceiptJpaEntity;
import com.stockflow.procurement.internal.entity.ReturnReceiptLooseLineJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
class ReturnReceiptRepositoryAdapter implements ReturnReceiptRepository, ReturnReceiptSearch {

    private final ReturnReceiptJpaRepository jpa;
    private final EntityManager entityManager;

    ReturnReceiptRepositoryAdapter(ReturnReceiptJpaRepository jpa, EntityManager entityManager) {
        this.jpa = jpa;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ReturnReceipt> findById(UUID receiptId) {
        return jpa.findByIdWithCartons(receiptId).map(ReturnReceiptRepositoryAdapter::toDomain);
    }

    /**
     * Locked first with a native {@code FOR UPDATE}, then read and refreshed, as
     * {@code GoodsReceiptRepositoryAdapter.findByIdForUpdate}: a caller that queued behind another transaction must
     * see what it committed, not a copy the persistence context held from before the wait.
     */
    @Override
    public Optional<ReturnReceipt> findByIdForUpdate(UUID receiptId) {
        if (entityManager.createNativeQuery("""
                        select id from procurement.goods_receipts where id = :id and receipt_type = 'RETURN'
                        for update""")
                .setParameter("id", receiptId).getResultList().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findByIdWithCartons(receiptId).map(entity -> {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return toDomain(entity);
        });
    }

    /**
     * Cartons are synchronised by id: a carton scanned again keeps its row (only its condition changes), one removed
     * from the draft is deleted, a new one inserted. Flushed here, so a refusal of the database's
     * ({@code check_return_receipt}, {@code check_return_receipt_carton}, the one-receipt-per-source indexes) is an
     * error of this call rather than of the commit.
     */
    @Override
    public ReturnReceipt save(ReturnReceipt receipt) {
        ReturnReceiptJpaEntity entity = jpa.findByIdWithCartons(receipt.id()).orElseGet(() ->
                new ReturnReceiptJpaEntity(receipt.id(), receipt.number(), receipt.warehouseId(), receipt.orderId(),
                        receipt.shipmentId(), receipt.returnRequestId(), receipt.quarantineLocationId(),
                        receipt.receivedAt(), receipt.receivedBy(), receipt.note()));
        Map<UUID, ReturnReceipt.Carton> wanted = receipt.cartons().stream()
                .collect(Collectors.toMap(ReturnReceipt.Carton::id, Function.identity()));
        Map<UUID, ReturnReceipt.LooseLine> wantedLoose = receipt.looseLines().stream()
                .collect(Collectors.toMap(ReturnReceipt.LooseLine::id, Function.identity()));
        boolean removed = entity.getCartons().removeIf(row -> !wanted.containsKey(row.getId()));
        removed |= entity.getLooseLines().removeIf(row -> !wantedLoose.containsKey(row.getId()));
        if (removed && !entity.isNew()) {
            entityManager.flush();
        }
        Map<UUID, ReturnReceiptCartonJpaEntity> rows = entity.getCartons().stream()
                .collect(Collectors.toMap(ReturnReceiptCartonJpaEntity::getId, Function.identity()));
        for (ReturnReceipt.Carton carton : receipt.cartons()) {
            ReturnReceiptCartonJpaEntity row = rows.get(carton.id());
            if (row == null) {
                entity.addCarton(new ReturnReceiptCartonJpaEntity(carton.id(), carton.stockPackageId(), carton.lpn(),
                        carton.sku(), carton.lotNumber(), carton.quantity(), carton.condition(), carton.scannedBy(),
                        carton.countedQuantity(), carton.countedBy()));
            } else {
                if (!java.util.Objects.equals(row.getConditionNote(), carton.condition())) {
                    row.noteCondition(carton.condition());
                }
                if (row.getCountedQty() != carton.countedQuantity()
                        || !row.getCountedBy().equals(carton.countedBy())) {
                    row.recount(carton.countedQuantity(), carton.countedBy());
                }
            }
        }
        Map<UUID, ReturnReceiptLooseLineJpaEntity> looseRows = entity.getLooseLines().stream()
                .collect(Collectors.toMap(ReturnReceiptLooseLineJpaEntity::getId, Function.identity()));
        for (ReturnReceipt.LooseLine line : receipt.looseLines()) {
            ReturnReceiptLooseLineJpaEntity row = looseRows.get(line.id());
            if (row == null) {
                entity.addLooseLine(new ReturnReceiptLooseLineJpaEntity(line.id(), line.sku(), line.lotNumber(),
                        line.quantity(), line.recordedBy(), line.photoId()));
            } else if (row.getQuantity() != line.quantity() || !row.getRecordedBy().equals(line.recordedBy())
                    || !java.util.Objects.equals(row.getPhotoId(), line.photoId())) {
                row.recount(line.quantity(), line.recordedBy(), line.photoId());
            }
        }
        entity.apply(receipt.status(), receipt.confirmedAt(), receipt.confirmedBy(), receipt.closedAt());
        ReturnReceiptJpaEntity saved = jpa.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<ReturnReceipt> findDraftForShipment(UUID shipmentId) {
        return jpa.findByShipmentInStatus(shipmentId, GoodsReceiptStatus.DRAFT).stream().findFirst()
                .flatMap(this::findById);
    }

    @Override
    public java.util.Set<String> lpnsReceivedForShipment(UUID shipmentId) {
        return new java.util.HashSet<>(jpa.lpnsOnShipmentReceipts(shipmentId, GoodsReceiptStatus.CLOSED));
    }

    @Override
    public long unitsReceivedForReturn(UUID returnRequestId, String sku, UUID exceptLooseLine) {
        // The database's own count (V20261218000400), so the service and check_return_receipt_loose_line agree. No
        // line to leave out is the nil UUID, which no line has: a null bound into a native query has no SQL type.
        return jpa.unitsReceivedForReturn(returnRequestId, sku,
                exceptLooseLine == null ? new UUID(0L, 0L) : exceptLooseLine);
    }

    @Override
    public Optional<ReturnReceipt> findDraftForReturnRequest(UUID returnRequestId) {
        return jpa.findByReturnRequestInStatus(returnRequestId, GoodsReceiptStatus.DRAFT).stream().findFirst()
                .flatMap(this::findById);
    }

    @Override
    public Page<Row> search(Criteria criteria, Pageable pageable) {
        Specification<ReturnReceiptJpaEntity> spec = Specification
                .<ReturnReceiptJpaEntity>where(Specs.eq("warehouseId", criteria.warehouseId()))
                .and(Specs.eq("customerOrderId", criteria.orderId()))
                .and(Specs.eq("returnShipmentId", criteria.shipmentId()))
                .and(Specs.eq("returnRequestId", criteria.returnRequestId()))
                .and(Specs.contains("receiptNumber", criteria.number()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty()
                        ? null : Specs.in("status", criteria.statuses()))
                .and(WarehouseScope.warehousesIn("warehouseId"));
        Page<ReturnReceiptJpaEntity> page = jpa.findAll(spec, pageable);
        Set<UUID> ids = page.getContent().stream().map(ReturnReceiptJpaEntity::getId).collect(Collectors.toSet());
        Map<UUID, Integer> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            jpa.countCartons(ids).forEach(r -> counts.put((UUID) r[0], ((Number) r[1]).intValue()));
        }
        return page.map(e -> new Row(e.getId(), e.getReceiptNumber(), e.getStatus(), e.getWarehouseId(),
                e.getCustomerOrderId(), e.getReturnShipmentId(), e.getReturnRequestId(), e.getReceivedAt(),
                e.getReceivedBy(), e.getClosedAt(), counts.getOrDefault(e.getId(), 0)));
    }

    @Override
    public List<CartonReceipt> draftsListing(UUID stockPackageId) {
        java.util.Optional<java.util.Set<UUID>> allowed = WarehouseScope.restriction()
                .map(com.stockflow.common.security.AssignedWarehouses::ids);
        return jpa.draftsListing(stockPackageId, GoodsReceiptStatus.DRAFT).stream()
                .filter(r -> allowed.map(ids -> ids.contains(r.getWarehouseId())).orElse(true))
                .map(r -> new CartonReceipt(r.getId(), r.getReceiptNumber(), r.getStatus(), r.getWarehouseId(),
                        r.getQuarantineLocationId()))
                .toList();
    }

    static ReturnReceipt toDomain(ReturnReceiptJpaEntity e) {
        List<ReturnReceipt.Carton> cartons = e.getCartons().stream()
                .sorted(java.util.Comparator.comparing(ReturnReceiptCartonJpaEntity::getId))
                .map(c -> new ReturnReceipt.Carton(c.getId(), c.getStockPackageId(), c.getLpn(), c.getSku(),
                        c.getLotNumber(), c.getQuantity(), c.getConditionNote(), c.getScannedBy(), c.getScannedAt(),
                        c.getCountedQty(), c.getCountedBy()))
                .toList();
        List<ReturnReceipt.LooseLine> loose = e.getLooseLines().stream()
                .sorted(java.util.Comparator.comparing(ReturnReceiptLooseLineJpaEntity::getId))
                .map(l -> new ReturnReceipt.LooseLine(l.getId(), l.getSku(), l.getLotNumber(), l.getQuantity(),
                        l.getRecordedBy(), l.getPhotoId()))
                .toList();
        return new ReturnReceipt(e.getId(), e.getReceiptNumber(), e.getWarehouseId(), e.getCustomerOrderId(),
                e.getReturnShipmentId(), e.getReturnRequestId(), e.getQuarantineLocationId(), e.getReceivedAt(),
                e.getReceivedBy(), e.getNote(), cartons, e.getStatus(), e.getConfirmedAt(), e.getConfirmedBy(),
                e.getClosedAt(), e.getVersion()).restoreLoose(loose)
                .restoreStocked(e.getCartons().stream().filter(c -> e.getStatus() != GoodsReceiptStatus.DRAFT)
                        .collect(Collectors.toMap(ReturnReceiptCartonJpaEntity::getId,
                                ReturnReceiptCartonJpaEntity::isStockEntered)));
    }
}
