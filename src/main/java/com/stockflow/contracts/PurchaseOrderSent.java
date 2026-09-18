package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Durable cross-module request to deliver one purchase order to its supplier. */
public record PurchaseOrderSent(UUID purchaseOrderId, String poNumber, UUID supplierId,
        String channel, String recipient, BigDecimal totalAmount, String currency,
        LocalDate expectedAt, int paymentTermDays) { }
