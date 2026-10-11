package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A warehouse as the application layer hands it out - never the aggregate itself. */
public record WarehouseSummary(UUID id, String prefix, String name, String address, String returnAddress,
                               MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight,
                               WarehouseStatus status, long version, Instant createdAt) {
}
