package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The whole count of a draft receipt, replacing the previous one. One line per PO line and lot;
 * {@code lotNumber} and {@code expiryDate} only for items tracked that way (BR-03).
 */
public record ReceiptLinesRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines) {

    public record Line(
            @NotNull UUID purchaseOrderLineId,
            @Positive int quantity,
            @Size(max = 64) String lotNumber,
            LocalDate expiryDate,
            @NotBlank @Size(max = 64) String locationCode,
            @Size(max = 255) String note
    ) {
    }
}
