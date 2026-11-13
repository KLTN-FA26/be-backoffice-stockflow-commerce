package com.stockflow.invoicing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Whose an issued e-invoice is, for a module that refers to one without owning it - the chat (kltn-docs 16 §4.6 step
 * 15: a conversation about an invoice of the customer, and package 3's invoice card).
 *
 * <p>Only an invoice that was issued (ISSUED, or REPLACED by a later one) is answered. A draft or a refused invoice is
 * the seller's working paper, and is "not found" here as it is on the customer's portal (CustomerEInvoiceController).</p>
 */
public interface IssuedEInvoices {

    /**
     * @param code the invoice's own code in this system
     */
    record IssuedEInvoice(UUID id, UUID customerId, String code, String status) {
    }

    Optional<IssuedEInvoice> find(UUID einvoiceId);

    /**
     * An issued invoice as the chat's invoice card shows it (kltn-docs 16 §4.6 step 15: number, symbol, date, total with
     * tax, the XML and PDF to download - through {@code GET /api/v1/einvoices/me/{id}/files/{XML|PDF}}).
     *
     * @param templateCode   the form's template digit (1: a VAT invoice)
     * @param symbol         the invoice's symbol, e.g. {@code C26TAA}
     * @param invoiceNumber  the number the provider gave it
     * @param hasXml         whether its XML is kept; likewise {@code hasPdf}
     */
    record Card(UUID id, UUID customerId, String code, String status, UUID orderId, String orderNumber,
                String templateCode, String symbol, Integer invoiceNumber, java.time.LocalDate issueDate,
                String currency, java.math.BigDecimal subtotal, java.math.BigDecimal taxTotal,
                java.math.BigDecimal grandTotal, boolean hasXml, boolean hasPdf) {
    }

    /** An issued (or replaced) invoice's card; empty for a draft, a refused one or none. */
    Optional<Card> card(UUID einvoiceId);
}
