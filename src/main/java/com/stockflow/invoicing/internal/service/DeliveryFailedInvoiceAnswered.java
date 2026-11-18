package com.stockflow.invoicing.internal.service;

import java.util.UUID;

/**
 * The provider answered for an invoice whose delivery was corrected to a failure while the invoice was at the provider
 * (WF-05 HĐ-3; {@code EInvoiceRepository.markDeliveryFailed}): it was issued, or refused. Published by
 * {@link EInvoiceIssuanceSteps} in the transaction that records the answer, and heard after it commits by
 * {@link DeliveryCorrectionListener}, which reduces the invoice in full or voids it and draws the shipment up again if
 * it was delivered again meanwhile.
 *
 * <p>An event of the invoicing module to itself, not a contract: nothing else needs to know. It goes through the event
 * publication registry like any other, so an answer recorded is settled even if the application stops right after -
 * the registry delivers it again. The id only: the listener reads the invoice under its locks.</p>
 */
public record DeliveryFailedInvoiceAnswered(UUID einvoiceId) {
}
