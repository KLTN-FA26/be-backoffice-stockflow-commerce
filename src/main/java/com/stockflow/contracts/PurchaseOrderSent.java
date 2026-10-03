package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Durable cross-module request to deliver one purchase order to its supplier. */
public record PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
        String channel, String recipient, BigDecimal totalAmount, String currency,
        LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines, int deliveryGeneration,
        Buyer buyer, boolean cancellation, String cancellationReason) {
    /** Compatibility constructor for publications created before buyer snapshots were introduced. */
    public PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines, int deliveryGeneration) {
        this(purchaseOrderId, poNumber, supplierId, channel, recipient, totalAmount, currency,
                expectedAt, paymentTermDays, lines, deliveryGeneration, null, false, null);
    }
    public PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, java.util.List<Line> lines) {
        this(purchaseOrderId, poNumber, supplierId, channel, recipient, totalAmount, currency,
                expectedAt, paymentTermDays, lines, 0);
    }
    public PurchaseOrderSent {
        lines = lines == null ? java.util.List.of() : java.util.List.copyOf(lines);
    }
    public record Line(String sku, String description, int quantity, BigDecimal unitPrice) { }
    public record Buyer(String companyName, String companyAddress, String contactName,
            String phone, String email, String receivingAddress) { }
    public String operationReference() {
        return "purchase-order:" + purchaseOrderId + (cancellation ? ":cancellation" : "");
    }
    public String templateCode() { return cancellation ? "purchase-order.cancelled" : "purchase-order.sent"; }
}
