package com.stockflow.invoicing.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An issued e-invoice as its buyer sees it on the portal: what it is quoted by and what it comes to. Nothing of the
 * seller's work on it - no deadline, warning, rejection or provider detail.
 *
 * <p>{@code replaces} and {@code replacedBy} state the replacement chain (TT 91 Điều 10) as the invoices themselves
 * do, so the portal links an invoice to its replacement without inferring it from the order, the shipment and the
 * type - an inference that a shipment invoiced again after a reversal would get wrong. Each names only an invoice the
 * customer can also see on this list.</p>
 *
 * @param status   ISSUED, or REPLACED for one another invoice has since replaced
 * @param adjusted whether an adjustment invoice has been issued against it
 */
public record CustomerEInvoiceResponse(UUID id, String type, String status, boolean adjusted, UUID orderId,
                                       String orderNumber, String shipmentCode, LocalDate issueDate,
                                       String templateCode, String symbol, Integer invoiceNumber, BigDecimal subtotal,
                                       BigDecimal taxTotal, BigDecimal grandTotal, Instant issuedAt,
                                       @Schema(description = "On a REPLACEMENT: the invoice it replaces (now "
                                               + "REPLACED). Null on any other invoice")
                                       Reference replaces,
                                       @Schema(description = "On a REPLACED invoice: the issued invoice that replaced "
                                               + "it - itself REPLACED if it was replaced again, so follow the chain one "
                                               + "link at a time. Null while no replacement has been issued: one still "
                                               + "being drawn up or at the provider is not shown")
                                       Reference replacedBy) {

    /** Another of the customer's issued invoices, by its id (the key of this list) and its code. */
    @Schema(name = "CustomerEInvoiceReference")
    public record Reference(UUID id, String code) {
    }
}
