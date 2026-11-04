package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One supplier that sells a given SKU, as a buyer comparing offers sees it: the supplier's status
 * is included because an inactive supplier still lists its old offers but cannot be ordered from.
 */
public record SkuSupplierSummary(
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
