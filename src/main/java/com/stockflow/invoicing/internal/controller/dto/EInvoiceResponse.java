package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One e-invoice as staff read it (WF-05 E2). {@code version} goes back with every change. The number, symbol, the tax
 * authority's code and the look-up code are there once it is issued; {@code overdue} says it is still to issue past its
 * deadline ({@code issueDueAt}: the end of the working day after {@code issueDate}).
 */
public record EInvoiceResponse(UUID id, String code, String type, String status, boolean adjusted, UUID orderId,
                               String orderNumber, String contractNumber, UUID shipmentId, String shipmentCode,
                               UUID deliveryBatchId, UUID customerId, UUID originalEInvoiceId, String provider,
                               String templateCode, String symbol, Integer invoiceNumber, String taxAuthorityCode,
                               String lookupCode, LocalDate issueDate, Instant issueDueAt, boolean overdue,
                               Instant signedAt, Party seller, Party buyer, String currency, String paymentMethod,
                               List<Line> lines, List<TaxRateTotal> totalsByRate, BigDecimal subtotal,
                               BigDecimal taxTotal, BigDecimal grandTotal, String amountInWords, List<String> warnings,
                               String rejectionCode, String rejectionMessage, Instant submittedAt, Instant issuedAt,
                               Instant voidedAt, String voidReason, boolean hasFiles, long version) {

    public record Party(String name, String taxCode, String address, String email) {
    }

    public record Line(UUID id, int lineNo, String kind, String feeKind, UUID orderLineId, String sku, String itemName,
                       String unit, int quantity, Integer unitsPerCarton, BigDecimal unitPrice, BigDecimal taxRate,
                       BigDecimal amount, BigDecimal taxAmount) {
    }

    public record TaxRateTotal(BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount) {
    }
}
