package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SupplierPerformanceResponse(UUID supplierId, long totalPurchaseOrders,
        long fulfilledPurchaseOrders, long onTimeOrders, long lateOrders,
        BigDecimal onTimeDeliveryRate, BigDecimal averageLeadTimeDays,
        long acceptedQuantity, long rejectedQuantity, BigDecimal qualityAcceptanceRate,
        Instant calculatedAt) { }
