package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SkuSupplierResponse(
        UUID supplierId,
        String supplierCode,
        String supplierName,
        String supplierStatus,
        UUID supplierItemId,
        String supplierSkuCode,
        BigDecimal price,
        String currency,
        int moq,
        int packSize,
        int effectiveLeadTimeDays,
        boolean preferred) {}
