package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Durable cross-module request to deliver one purchase order to its supplier. */
public record PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
        String channel, String recipient, BigDecimal totalAmount, String currency,
        LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines) {
    public PurchaseOrderSent {
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
    }
    public record Line(String sku, String description, int quantity, BigDecimal unitPrice) { }
}
