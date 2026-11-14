package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A row of the accountant's list of e-invoices. */
public record EInvoiceListItemResponse(UUID id, String code, String type, String status, boolean adjusted,
                                       UUID orderId, String orderNumber, UUID shipmentId, String shipmentCode,
                                       UUID customerId, String buyerName, String buyerTaxCode, LocalDate issueDate,
                                       Instant issueDueAt, boolean overdue, String templateCode, String symbol,
                                       Integer invoiceNumber, BigDecimal subtotal, BigDecimal taxTotal,
                                       BigDecimal grandTotal, List<String> warnings, String rejectionCode,
                                       Instant issuedAt) {
}
