package com.stockflow.procurement.internal.domain;
import java.math.BigDecimal;
public record SupplierMetrics(long totalPurchaseOrders, long fulfilledPurchaseOrders, long onTimeOrders,
        long lateOrders, BigDecimal averageLeadTimeDays, long acceptedQuantity, long rejectedQuantity) {}
