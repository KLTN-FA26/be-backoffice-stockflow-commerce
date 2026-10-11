package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.MapUnit;

import java.math.BigDecimal;

/** A new warehouse and its map frame. {@code prefix} is upper-cased and then never changes. */
public record RegisterWarehouseCommand(String prefix, String name, String address, String returnAddress,
                                       MapUnit mapUnit, BigDecimal mapWidth, BigDecimal mapHeight) {
}
