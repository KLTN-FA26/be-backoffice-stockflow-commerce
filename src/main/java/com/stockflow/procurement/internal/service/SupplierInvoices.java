package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.procurement.internal.domain.InvoiceVarianceKind;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierInvoiceStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplier invoices (SCRUM-308) and their three-way match against the purchase order and its receipts (SCRUM-309;
 * kltn-docs 04, docs 03 §4, BR-INV-001/002): enter, correct, match, cancel, attach the scan.
 *
 * <p>Not on {@code procurement :: api}: nothing outside this module drives an invoice. Resolving variances, approving
 * payment and recording it (SCRUM-310) start from what this leaves: MATCHED, or EXCEPTION with its variances.</p>
 */
public interface SupplierInvoices {

    /** One line as typed from the paper; {@code taxRate} null is 0. */
    record LineInput(UUID purchaseOrderLineId, UUID receiptLineId, int quantity, BigDecimal unitPrice,
                     BigDecimal taxRate) {
    }

    /**
     * A new invoice. {@code purchaseOrderId} is the one order it bills, or null for an invoice over several orders of
     * the supplier; {@code currency} null takes the orders'.
     */
    record Enter(UUID supplierId, UUID purchaseOrderId, String invoiceNumber, LocalDate invoiceDate,
                 LocalDate dueDate, String currency, String note, List<LineInput> lines) {
    }

    /**
     * The whole editable invoice again - the order it names too, null for several; {@code version}, when given, is the
     * one the accountant read.
     */
    record Revise(UUID purchaseOrderId, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, String note,
                  List<LineInput> lines, Long version) {
    }

    record LineView(UUID id, UUID purchaseOrderLineId, UUID purchaseOrderId, String purchaseOrderNumber, int lineNo,
                    String sku, UUID receiptLineId, int quantity, BigDecimal unitPrice, BigDecimal taxRate,
                    BigDecimal netAmount, BigDecimal taxAmount, BigDecimal total) {
    }

    record VarianceView(UUID id, UUID lineId, UUID purchaseOrderId, InvoiceVarianceKind kind, BigDecimal expected,
                        BigDecimal invoiced, BigDecimal amount) {
    }

    record InvoiceView(UUID id, UUID supplierId, String supplierName, UUID purchaseOrderId, String invoiceNumber,
                       LocalDate invoiceDate, LocalDate dueDate, String currency, String note,
                       SupplierInvoiceStatus status, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
                       List<LineView> lines, List<VarianceView> variances, String scanName, String scanContentType,
                       Instant receivedAt, UUID receivedBy, Instant matchedAt, UUID matchedBy,
                       BigDecimal priceTolerancePercent, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                       long version) {
    }

    record Row(UUID id, String invoiceNumber, UUID supplierId, String supplierName, UUID purchaseOrderId,
               SupplierInvoiceStatus status, LocalDate invoiceDate, LocalDate dueDate, String currency,
               BigDecimal total, int varianceCount) {
    }

    /**
     * What an invoice of one order may bill, per line (kltn-docs 04 §4 step 2): ordered, billable (received and passed
     * QC; for a SUBCONTRACT order, passed by its final settlement), awaiting QC, billed already by MATCHED invoices,
     * and the price and tax to bill at.
     */
    record BasisLine(UUID purchaseOrderLineId, int lineNo, String sku, int ordered, int billable, int awaitingQc,
                     int invoiced, BigDecimal unitPrice, BigDecimal discountRate, BigDecimal netUnitPrice,
                     BigDecimal taxRate) {
    }

    /** {@code settled} is null for a STANDARD order; for a SUBCONTRACT one, whether its settlement is final. */
    record Basis(UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId, PurchaseOrderStatus status,
                 String currency, boolean subcontract, Boolean settled, BigDecimal settlementNetPayable,
                 BigDecimal priceTolerancePercent, List<BasisLine> lines) {
    }

    InvoiceView enter(Enter command, UUID by);

    InvoiceView revise(UUID invoiceId, Revise command);

    /** Runs the three-way match: MATCHED, or EXCEPTION with what it found. Again on an EXCEPTION, after QC or a fix. */
    InvoiceView match(UUID invoiceId, UUID by);

    InvoiceView cancel(UUID invoiceId, String reason, UUID by);

    InvoiceView attachScan(UUID invoiceId, FileUpload upload);

    DownloadLink scanLink(UUID invoiceId);

    InvoiceView get(UUID invoiceId);

    /**
     * Newest invoice date first unless sorted otherwise; every filter null means any. {@code purchaseOrderId} keeps the
     * invoices of that order and those over several orders with a line on it; {@code number} is part of the number.
     */
    PageResponse<Row> list(List<SupplierInvoiceStatus> statuses, UUID supplierId, UUID purchaseOrderId, String number,
                           LocalDate invoicedFrom, LocalDate invoicedTo, int page, int size, String sort);

    Basis basis(UUID purchaseOrderId);
}
