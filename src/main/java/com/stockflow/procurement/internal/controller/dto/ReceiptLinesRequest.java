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
 *
 * <p>SCRUM-473: {@code quantity} stays the line's total in base units. {@code packages} are the labelled cartons
 * holding part or all of it, the whole list each time - an entry with an {@code lpn} recounts that carton, one
 * without labels a new one, a carton left out is voided - and {@code unitOfMeasure} is then the active packing unit
 * they are in. A line without cartons is loose, in the base unit.</p>
 */
public record ReceiptLinesRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines) {

    public record Line(
            @NotNull UUID purchaseOrderLineId,
            @Positive int quantity,
            @Size(max = 64) String lotNumber,
            LocalDate expiryDate,
            @NotBlank @Size(max = 64) String locationCode,
            @Size(max = 255) String note,
            @Size(max = 16) String unitOfMeasure,
            @Size(max = 500) List<@Valid @NotNull Package> packages
    ) {
    }

    /** One carton: {@code lpn} to recount one labelled before, absent to label a new one. */
    public record Package(@Size(max = 30) String lpn, @Positive int quantity) {
    }
}
