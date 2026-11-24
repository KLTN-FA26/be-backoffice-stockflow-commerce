package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The whole count of a draft receipt against a production order, replacing the previous one (4A-5): one line per
 * production order line, naming its {@code productionOrderLineId} and listing at least one carton in
 * {@code packages} - an entry with an {@code lpn} recounts that carton, one without labels a new one, a carton left out
 * is voided (SCRUM-473).
 *
 * <p>No count of pieces, neither the line's nor a carton's: every carton holds the SKU's standard count, which the
 * system assigns (kltn-docs 03 §3, BR-13), and a carton of another count is not listed at all - it goes back to the
 * producer. The warehouse counts cartons; the line's pieces are its cartons times the standard count.</p>
 *
 * <p>{@code lotNumber} is refused on its field when given: the lot is the production order's code (BR-03). There is no
 * expiry date (BR-06; team plan 5-1). {@code sku}, when given, must be the production order
 * line's. The purchase order line a line once named went with purchasing (4A-8).</p>
 */
public record ReceiptLinesRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull Line> lines) {

    public record Line(
            UUID productionOrderLineId,
            @Size(max = 64) String lotNumber,
            @NotBlank @Size(max = 64) String locationCode,
            @Size(max = 255) String note,
            @Size(max = 16) String unitOfMeasure,
            @Size(max = 500) List<@Valid @NotNull Package> packages,
            @Size(max = 64) String sku
    ) {
    }

    /** One carton: {@code lpn} to recount one labelled before, absent to label a new one. */
    public record Package(@Size(max = 30) String lpn) {
    }
}
