package com.stockflow.catalog.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A customer group's price for a SKU, per piece, and the version to send back on change. */
public record SegmentPriceResponse(UUID segmentId, String sku, BigDecimal price, String currency, long version) {
}
