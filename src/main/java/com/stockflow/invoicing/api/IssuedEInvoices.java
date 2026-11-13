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
}
