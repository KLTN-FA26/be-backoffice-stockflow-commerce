package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stockflow.common.label.LabelFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Which carton labels to print (SCRUM-473): the live cartons of a goods receipt line, or named cartons to reprint.
 * At most one is chosen; neither is answered by the service with {@code STOCK_PACKAGE_LABELS_EMPTY}. The count of
 * LPNs is not capped here so that an oversized print gets {@code STOCK_PACKAGE_LABELS_TOO_MANY}, the same 422 a
 * receipt line with too many cartons gets, instead of a 400.
 */
@Schema(description = "Print carton labels. One of receiptLineId (every live carton of that goods receipt "
        + "line) and lpns (reprint these cartons). At most 500 labels")
public record StockPackageLabelRequest(
        @Schema(description = "A4_SHEET: 24 labels of 70 x 37 mm per A4 page. THERMAL_ROLL: one 60 x 40 mm "
                + "label per page", example = "THERMAL_ROLL")
        @NotNull LabelFormat format,
        @Schema(description = "Every carton of this receipt line that is not VOID, CONSUMED or LOST")
        UUID receiptLineId,
        @Schema(description = "Cartons to reprint, by LPN; case does not matter",
                example = "[\"LPN-20261026-000001\"]")
        List<@NotBlank @Size(max = 30) String> lpns
) {

    /** An empty {@code lpns} names nothing, so it is no second selector: a form may always send {@code []}. */
    @JsonIgnore
    @AssertTrue(message = "Send receiptLineId or lpns, not both")
    public boolean isOneSelector() {
        return receiptLineId == null || lpns == null || lpns.isEmpty();
    }
}
