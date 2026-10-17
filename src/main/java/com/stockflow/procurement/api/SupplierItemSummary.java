package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a supplier's catalog.
 *
 * @param leadTimeDays          the item's own lead time, {@code null} when it uses the supplier's
 * @param effectiveLeadTimeDays what a purchase order would actually plan with
 */
public record SupplierItemSummary(
        UUID supplierItemId,
        UUID supplierId,
        UUID inventoryItemId,
        String sku,
        String supplierSkuCode,
        BigDecimal price,
        String currency,
        int moq,
        int packSize,
        Integer leadTimeDays,
        int effectiveLeadTimeDays,
        boolean preferred,
        long version,
        Instant createdAt,
        Instant lastModifiedAt) {}
