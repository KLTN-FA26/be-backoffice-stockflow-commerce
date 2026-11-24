package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A row of the accountant's list of e-invoices. {@code correctionBasis}: why an adjustment or a replacement corrects
 * (ERROR, RETURN, TRADE_DISCOUNT, DELIVERY_FAILED), null on an original; {@code reversedAt}: when the invoice was reduced
 * in full for a failed delivery, null otherwise (WF-05 F-11, BE-36); {@code deliveredOn}: the day of the delivery an
 * original invoices ({@code issueDate} is later on one issued late), null on a correction (Q-1).
 */
public record EInvoiceListItemResponse(UUID id, String code, String type, String status, boolean adjusted,
                                       UUID orderId, String orderNumber, UUID shipmentId, String shipmentCode,
                                       UUID customerId, String buyerName, String buyerTaxCode, LocalDate issueDate,
                                       Instant issueDueAt, boolean overdue, String templateCode, String symbol,
                                       Integer invoiceNumber, BigDecimal subtotal, BigDecimal taxTotal,
                                       BigDecimal grandTotal, List<String> warnings, String rejectionCode,
                                       Instant issuedAt, String correctionBasis, Instant reversedAt,
                                       LocalDate deliveredOn) {
}
