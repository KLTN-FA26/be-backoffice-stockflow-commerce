package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SupplierItemResponse(
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
