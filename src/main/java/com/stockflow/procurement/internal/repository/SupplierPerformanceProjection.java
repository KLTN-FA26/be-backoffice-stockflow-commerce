package com.stockflow.procurement.internal.repository;

import java.math.BigDecimal;

public interface SupplierPerformanceProjection {
    long getTotalPurchaseOrders();
    long getFulfilledPurchaseOrders();
    long getOnTimeOrders();
    long getLateOrders();
    BigDecimal getAverageLeadTimeDays();
    long getAcceptedQuantity();
    long getRejectedQuantity();
}
