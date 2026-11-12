package com.stockflow.invoicing.internal.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: when an invoice was last sent to the provider, and under which transaction id
 * ({@code invoicing.einvoice_sending}).
 *
 * <p>The quiet period before a transaction the provider does not know is sent again runs from the last send, so the
 * last send has to be known; and it is recorded <b>before</b> the provider is called, so a send the application did
 * not live to see the end of still counts. Kept apart from the invoice: a send does not change the invoice, and must
 * not advance the version the accountant acts on.</p>
 */
public interface EInvoiceSending {

    /** When the invoice was last sent under {@code transactionId}; empty when it never was. */
    Optional<Instant> lastSent(UUID einvoiceId, String transactionId);

    /** Records that the invoice is sent now under {@code transactionId}, replacing what an earlier send recorded. */
    void sent(UUID einvoiceId, String transactionId, Instant at);
}
