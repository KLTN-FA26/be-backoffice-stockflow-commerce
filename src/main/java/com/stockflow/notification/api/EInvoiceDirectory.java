package com.stockflow.notification.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An issued e-invoice as its buyer is sent it: asked by notification when it delivers {@code EInvoiceIssued},
 * <b>implemented by the invoicing module</b> (WF-05 E5).
 *
 * <p>The event carries ids and codes only - it is stored in {@code event_publication}, where neither the buyer's
 * address nor the code an invoice is looked up by belongs ({@code verify.py} check 16b) - and notification depends on
 * no business module. So the dependency is inverted, as {@link CustomerDirectory} and {@link ProductionOrderDirectory}
 * are: notification declares what it needs, invoicing provides it. A module test that boots notification without
 * invoicing mocks this interface.</p>
 */
public interface EInvoiceDirectory {

    /**
     * The invoice as the message to its buyer words it; empty when there is no such invoice or it was never issued.
     * One read of the invoice, no file.
     */
    Optional<EInvoiceMail> mail(UUID einvoiceId);

    /**
     * The invoice's PDF and XML as stored when it was issued, in that order - the same bytes on every call, which a
     * retry under the provider's idempotency key depends on. Empty when there is no such issued invoice.
     *
     * @throws RuntimeException when the file store does not answer: an outage, and the caller tries again later
     */
    List<EInvoiceMail.File> files(UUID einvoiceId);
}
