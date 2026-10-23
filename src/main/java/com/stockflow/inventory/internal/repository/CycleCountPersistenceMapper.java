package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import com.stockflow.inventory.internal.entity.CycleCountLineJpaEntity;

/**
 * Hand-written for the reason {@link StockItemPersistenceMapper} gives: rehydration goes through the domain
 * constructors so the invariants are re-checked, and the slot is a value object over four columns.
 */
final class CycleCountPersistenceMapper {

    private CycleCountPersistenceMapper() {
    }

    static CycleCount toDomain(CycleCountJpaEntity e) {
        return new CycleCount(e.getId(), e.getCountNumber(), e.getWarehouseId(), e.getTriggerType(), e.getStatus(),
                e.isBlind(), e.getAssignedUserId(), e.getPlannedDate(), e.getStartedAt(), e.getCompletedAt(),
                e.getNote(), e.getCreatedAt(),
                e.getLines().stream().map(CycleCountPersistenceMapper::toDomain).toList(), e.getVersion());
    }

    private static CycleCountLine toDomain(CycleCountLineJpaEntity l) {
        StockPackageId pkg = l.getStockPackageId() == null ? null : new StockPackageId(l.getStockPackageId());
        return new CycleCountLine(l.getId(),
                new CountSlot(new LocationId(l.getLocationCode()), new Sku(l.getSku()), l.getLotNumber(), pkg),
                l.getExpectedQty(), l.getCountedQty(), l.getCountedBy(), l.getCountedAt(), l.isRecountRequired());
    }

    static CycleCountJpaEntity toNewEntity(CycleCount count) {
        CycleCountJpaEntity entity = new CycleCountJpaEntity(count.id(), count.number(), count.warehouseId(),
                count.trigger(), count.blind(), count.plannedDate());
        count.lines().forEach(line -> entity.addLine(toNewEntity(line)));
        applyHeader(count, entity);
        return entity;
    }

    /** Copies what the aggregate moved onto the managed row: its header, each line's count, and any added line. */
    static void apply(CycleCount count, CycleCountJpaEntity entity) {
        applyHeader(count, entity);
        for (CycleCountLine line : count.lines()) {
            entity.getLines().stream().filter(row -> row.getId().equals(line.id())).findFirst()
                    .ifPresentOrElse(row -> applyCount(line, row), () -> entity.addLine(toNewEntity(line)));
        }
    }

    private static void applyHeader(CycleCount count, CycleCountJpaEntity entity) {
        entity.apply(count.status(), count.assignedUserId(), count.startedAt(), count.completedAt(), count.note());
    }

    private static void applyCount(CycleCountLine line, CycleCountLineJpaEntity row) {
        row.apply(line.expectedQty(), line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired());
    }

    private static CycleCountLineJpaEntity toNewEntity(CycleCountLine line) {
        CountSlot slot = line.slot();
        return new CycleCountLineJpaEntity(line.id(), slot.location().code(), slot.sku().code(), slot.lotNumber(),
                slot.stockPackageId() == null ? null : slot.stockPackageId().value(), line.expectedQty(),
                line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired());
    }
}
