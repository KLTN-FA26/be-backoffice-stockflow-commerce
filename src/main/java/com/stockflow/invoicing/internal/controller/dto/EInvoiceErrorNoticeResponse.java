package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A 04/SS-HĐĐT notice of an e-invoice (WF-05 E4). {@code status}: DRAFT (recorded, not yet with the provider), SENT,
 * ACCEPTED or REJECTED, the last two with the tax authority answer in {@code responseCode} / {@code responseMessage}.
 */
public record EInvoiceErrorNoticeResponse(UUID id, UUID einvoiceId, String formCode, String subject, String reason,
                                          String correctBuyerName, String correctBuyerAddress, String status,
                                          Instant sentAt, String responseCode, String responseMessage,
                                          Instant respondedAt, UUID recordedBy, Instant recordedAt) {
}
