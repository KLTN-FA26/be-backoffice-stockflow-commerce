package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How the customer pays one installment by bank transfer (4B-6, kltn-docs 15 §4.2 step 5): the company's account, what
 * is still owed, and what to write in the transfer - on the portal, and the card Sales sends in the chat (16).
 */
@Schema(name = "InstallmentTransferInstructions")
public record InstallmentTransferResponse(UUID installmentId, String contractNumber, String orderNumber, int seq,
                                          String name, String bankName, String accountNumber, String accountName,
                                          BigDecimal amountDue, String currency,
                                          @Schema(example = "HD-20261108-000007-1 DOT1") String reference,
                                          @Schema(description = "Null before its milestone and for an installment paid "
                                                  + "before delivery") LocalDate dueDate,
                                          @Schema(description = "R5-9: the VietQR (EMVCo) payload for paying amountDue "
                                                  + "with reference, the same string the chat's transfer card carries; "
                                                  + "null when the company's account has no bank BIN configured or "
                                                  + "the amount is not in VND") String vietQr) {
}
