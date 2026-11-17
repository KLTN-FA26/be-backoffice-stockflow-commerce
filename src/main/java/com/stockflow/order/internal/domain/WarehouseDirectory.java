package com.stockflow.order.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** The warehouse an order is released from: does it exist, and its code for {@code OrderReleased}. */
public interface WarehouseDirectory {

    Optional<String> codeOf(UUID warehouseId);

    /** 4B-7: the warehouses an order may ship from - every ACTIVE one, by code. */
    java.util.List<Warehouse> active();

    /** A warehouse as an order sees it. */
    record Warehouse(UUID id, String code, String name) {
    }
}
