package com.stockflow.invoicing.internal.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An issued e-invoice as its buyer sees it on the portal: what it is quoted by and what it comes to. Nothing of the
 * seller's work on it - no deadline, warning, rejection or provider detail.
 *
 * @param status   ISSUED, or REPLACED for one another invoice has since replaced
 * @param adjusted whether an adjustment invoice has been issued against it
 */
public record CustomerEInvoiceResponse(UUID id, String type, String status, boolean adjusted, UUID orderId,
                                       String orderNumber, String shipmentCode, LocalDate issueDate,
                                       String templateCode, String symbol, Integer invoiceNumber, BigDecimal subtotal,
                                       BigDecimal taxTotal, BigDecimal grandTotal, Instant issuedAt) {
}
