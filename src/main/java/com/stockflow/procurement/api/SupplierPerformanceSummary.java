package com.stockflow.procurement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SupplierPerformanceSummary(UUID supplierId, long totalPurchaseOrders,
        long fulfilledPurchaseOrders, long onTimeOrders, long lateOrders,
        BigDecimal onTimeDeliveryRate, BigDecimal averageLeadTimeDays,
        long acceptedQuantity, long rejectedQuantity, BigDecimal qualityAcceptanceRate,
        Instant calculatedAt) { }
