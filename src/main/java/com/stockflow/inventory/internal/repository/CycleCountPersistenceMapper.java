package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import com.stockflow.inventory.internal.entity.CycleCountLineJpaEntity;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Copies what the aggregate moved onto the managed row: its header, each line's count (and a carton line's bin),
     * and any added line. The rows are indexed by id once, so a count of N lines costs N lookups, not N scans of N.
     */
    static void apply(CycleCount count, CycleCountJpaEntity entity) {
        applyHeader(count, entity);
        Map<UUID, CycleCountLineJpaEntity> rows = entity.getLines().stream()
                .collect(Collectors.toMap(CycleCountLineJpaEntity::getId, Function.identity()));
        for (CycleCountLine line : count.lines()) {
            CycleCountLineJpaEntity row = rows.get(line.id());
            if (row != null) {
                applyCount(line, row);
            } else {
                entity.addLine(toNewEntity(line));
            }
        }
    }

    private static void applyHeader(CycleCount count, CycleCountJpaEntity entity) {
        entity.apply(count.status(), count.assignedUserId(), count.startedAt(), count.completedAt(), count.note());
    }

    private static void applyCount(CycleCountLine line, CycleCountLineJpaEntity row) {
        if (!row.getLocationCode().equals(line.slot().location().code())) {
            row.relocate(line.slot().location().code());
        }
        row.apply(line.expectedQty(), line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired());
    }

    private static CycleCountLineJpaEntity toNewEntity(CycleCountLine line) {
        CountSlot slot = line.slot();
        return new CycleCountLineJpaEntity(line.id(), slot.location().code(), slot.sku().code(), slot.lotNumber(),
                slot.stockPackageId() == null ? null : slot.stockPackageId().value(), line.expectedQty(),
                line.countedQty(), line.countedBy(), line.countedAt(), line.recountRequired());
    }
}
