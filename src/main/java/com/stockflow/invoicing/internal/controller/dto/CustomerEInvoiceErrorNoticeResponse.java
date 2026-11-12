package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A 04/SS-HĐĐT notice as the buyer reads it on the portal (WF-05 E4/E5): the invoice it holds names it wrongly, here is
 * what it should have said, and whether the tax authority took the notice. {@code status}: DRAFT (recorded, not yet
 * with the tax authority), SENT, ACCEPTED or REJECTED. Who recorded it and the provider's codes are the seller's own
 * and are left out.
 *
 * @param correctBuyerName    the name the invoice should have carried; null when the name was right
 * @param correctBuyerAddress the address the invoice should have carried; null when the address was right
 */
public record CustomerEInvoiceErrorNoticeResponse(UUID id, UUID einvoiceId, String formCode, String reason,
                                                  String correctBuyerName, String correctBuyerAddress,
                                                  String status, Instant recordedAt, Instant respondedAt) {
}
