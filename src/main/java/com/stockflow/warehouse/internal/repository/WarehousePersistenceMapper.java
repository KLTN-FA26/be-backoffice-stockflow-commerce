package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.Zone;
import com.stockflow.warehouse.internal.entity.WarehouseJpaEntity;
import com.stockflow.warehouse.internal.entity.ZoneJpaEntity;

/** Entity to aggregate and back, for warehouses and zones. */
final class WarehousePersistenceMapper {

    private WarehousePersistenceMapper() {
    }

    static Warehouse toDomain(WarehouseJpaEntity entity) {
        return new Warehouse(entity.getId(), entity.getPrefix(), entity.getName(), entity.getAddress(),
                entity.getReturnAddress(), entity.getMapUnit(), entity.getMapWidth(),
                entity.getMapHeight(), entity.getStatus(), entity.getVersion(), entity.getCreatedAt());
    }

    static WarehouseJpaEntity toNewEntity(Warehouse warehouse) {
        return new WarehouseJpaEntity(warehouse.id(), warehouse.prefix(), warehouse.name(),
                warehouse.address(), warehouse.returnAddress(), warehouse.mapUnit(),
                warehouse.mapWidth(), warehouse.mapHeight(), warehouse.status());
    }

    static void apply(Warehouse warehouse, WarehouseJpaEntity entity) {
        entity.apply(warehouse.name(), warehouse.address(), warehouse.returnAddress(),
                warehouse.mapWidth(), warehouse.mapHeight(), warehouse.status());
    }

    static Zone toDomain(ZoneJpaEntity entity) {
        return new Zone(entity.getId(), entity.getWarehouseId(), entity.getName(), entity.getColor(),
                entity.getVersion());
    }

    static ZoneJpaEntity toNewEntity(Zone zone) {
        return new ZoneJpaEntity(zone.id(), zone.warehouseId(), zone.name(), zone.color());
    }

    static void apply(Zone zone, ZoneJpaEntity entity) {
        entity.apply(zone.name(), zone.color());
    }
}
