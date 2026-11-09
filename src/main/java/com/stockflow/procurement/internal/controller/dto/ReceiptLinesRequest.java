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
 * The whole count of a draft receipt against a production order, replacing the previous one (4A-5): one line per
 * production order line, naming its {@code productionOrderLineId} and listing at least one carton in
 * {@code packages} - an entry with an {@code lpn} recounts that carton, one without labels a new one, a carton left out
 * is voided (SCRUM-473). Every carton holds the line's standard count, so a carton's {@code quantity} and the line's
 * {@code quantity} may be left out (they are counted from the cartons) and, when given, must agree.
 *
 * <p>{@code lotNumber} and {@code expiryDate} are refused on their field when given: the lot is the production order's
 * code (BR-03), finished goods carry no expiry date (BR-06). {@code sku}, when given, must be the production order
 * line's. The purchase order line a line once named went with purchasing (4A-8).</p>
 */
public record ReceiptLinesRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines) {

    public record Line(
            UUID productionOrderLineId,
            @Positive Integer quantity,
            @Size(max = 64) String lotNumber,
            LocalDate expiryDate,
            @NotBlank @Size(max = 64) String locationCode,
            @Size(max = 255) String note,
            @Size(max = 16) String unitOfMeasure,
            @Size(max = 500) List<@Valid @NotNull Package> packages,
            @Size(max = 64) String sku
    ) {
    }

    /** One carton: {@code lpn} to recount one labelled before, absent to label a new one; {@code quantity} may be left out. */
    public record Package(@Size(max = 30) String lpn, @Positive Integer quantity) {
    }
}
