package com.stockflow.procurement.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies of the supplier invoice endpoints (SCRUM-308/309). */
public final class SupplierInvoiceResponses {

    private SupplierInvoiceResponses() {
    }

    /** One billed line, with the PO line it bills. Amounts: net before tax, tax at the line's rate, total both. */
    public record Line(UUID id, UUID purchaseOrderLineId, UUID purchaseOrderId, String purchaseOrderNumber, int lineNo,
                       String sku, UUID receiptLineId, int quantity, BigDecimal unitPrice, BigDecimal taxRate,
                       BigDecimal netAmount, BigDecimal taxAmount, BigDecimal total) {
    }

    /**
     * What the last match found. {@code kind}: OVER_INVOICED, OVER_INVOICED_RECEIPT, AWAITING_QC, SETTLEMENT_PENDING
     * (units; {@code expected} what was left to bill), PRICE (net unit price), TAX_RATE (percent), ABOVE_SETTLEMENT (of
     * a subcontract order, {@code lineId} null: net amounts). {@code amount} is the money at stake before tax, positive
     * when the supplier bills more than expected.
     */
    public record Variance(UUID id, UUID lineId, UUID purchaseOrderId, String kind, BigDecimal expected,
                           BigDecimal invoiced, BigDecimal amount) {
    }

    /**
     * {@code status}: DRAFT (entered, not matched), MATCHED, EXCEPTION (see {@code variances}), CANCELLED; DISPUTED,
     * APPROVED_FOR_PAYMENT and PAID come with resolving and paying (SCRUM-310). {@code priceTolerancePercent} is the
     * one the last match used.
     */
    public record Invoice(UUID id, UUID supplierId, String supplierName, UUID purchaseOrderId, String invoiceNumber,
                          LocalDate invoiceDate, LocalDate dueDate, String currency, String note, String status,
                          BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total, List<Line> lines,
                          List<Variance> variances, boolean hasScan, String scanName, String scanContentType,
                          Instant receivedAt, UUID receivedBy, Instant matchedAt, UUID matchedBy,
                          BigDecimal priceTolerancePercent, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                          long version) {
    }

    public record Row(UUID id, String invoiceNumber, UUID supplierId, String supplierName, UUID purchaseOrderId,
                      String status, LocalDate invoiceDate, LocalDate dueDate, String currency, BigDecimal total,
                      int varianceCount) {
    }

    /** A short-lived link to the scan, served by the object store. */
    public record ScanLink(String url, Instant expiresAt) {
    }

    /**
     * Per line of one order, what an invoice of it may bill: {@code billable} received and passed QC (for a SUBCONTRACT
     * order, passed by its final settlement), {@code awaitingQc} received but not concluded on, {@code invoiced} billed
     * by MATCHED invoices already, at {@code netUnitPrice} (the price less the line's discount) and {@code taxRate}.
     */
    public record BasisLine(UUID purchaseOrderLineId, int lineNo, String sku, int ordered, int billable,
                            int awaitingQc, int invoiced, BigDecimal unitPrice, BigDecimal discountRate,
                            BigDecimal netUnitPrice, BigDecimal taxRate) {
    }

    /** {@code settled}: null for a STANDARD order; whether a SUBCONTRACT order's settlement is final. */
    public record Basis(UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId, String status,
                       String currency, boolean subcontract, Boolean settled, BigDecimal settlementNetPayable,
                       BigDecimal priceTolerancePercent, List<BasisLine> lines) {
    }
}
