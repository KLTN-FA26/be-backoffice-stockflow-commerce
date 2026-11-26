package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The Accountant assigns a transfer waiting for review to installments of one customer (part C): the receipt is
 * recorded from it, under its bank reference, on the day it came in. The lines pay no more than the transfer brought;
 * what is left is settled in the accounting software (15 BR-06).
 */
@Schema(description = "Assign a bank transfer to installments: records the payment receipt")
public record AssignBankTransactionRequest(
        @NotNull(message = "customerId is required") UUID customerId,
        @NotEmpty(message = "a transfer pays at least one installment")
        @Size(max = 100, message = "at most 100 lines")
        List<RecordReceiptRequest.@Valid @NotNull(message = "a line is required") Line> lines,
        @Size(max = 500, message = "note is at most 500 characters") String note
) {
}
