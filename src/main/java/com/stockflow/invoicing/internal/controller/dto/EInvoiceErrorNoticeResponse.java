package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A 04/SS-HĐĐT notice of an e-invoice (WF-05 E4). {@code status}: DRAFT (recorded, not yet with the provider), SENT,
 * ACCEPTED or REJECTED, the last two with the tax authority answer in {@code responseCode} / {@code responseMessage}.
 * {@code formCode} is the form's identifier, in ASCII ({@code 04/SS-HDDT}); {@code formName} is its name as the law
 * prints it ({@code 04/SS-HĐĐT}), which is what a screen shows. {@code recordedByName} is who {@code recordedBy} is
 * (full name, else username), read once for the list; null when the account is gone.
 */
public record EInvoiceErrorNoticeResponse(UUID id, UUID einvoiceId, String formCode, String subject, String reason,
                                          String correctBuyerName, String correctBuyerAddress, String status,
                                          Instant sentAt, String responseCode, String responseMessage,
                                          Instant respondedAt, UUID recordedBy, Instant recordedAt, String formName,
                                          String recordedByName) {
}
