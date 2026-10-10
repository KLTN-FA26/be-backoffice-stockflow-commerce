package com.stockflow.order.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** The warehouse an order is released from: does it exist, and its code for {@code OrderReleased}. */
public interface WarehouseDirectory {

    Optional<String> codeOf(UUID warehouseId);
}
