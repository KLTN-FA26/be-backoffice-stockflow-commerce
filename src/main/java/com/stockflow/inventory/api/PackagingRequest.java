package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/** One line of a batch {@link PackagingSpecResolver#resolveAll}: a SKU and how many base units of it. */
public record PackagingRequest(Sku sku, int quantity) {
}
