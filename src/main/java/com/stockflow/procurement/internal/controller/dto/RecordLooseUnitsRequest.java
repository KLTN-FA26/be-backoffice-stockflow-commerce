package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Units that came back on a return receipt with no carton label (WF-05 TH-4): the SKU - one of the order's - the lot
 * when it can be read, how many. Recorded on the receipt, never put into stock.
 */
public record RecordLooseUnitsRequest(
        @NotBlank @Size(max = 64) String sku,
        @Size(max = 64) String lotNumber,
        @Min(value = 1, message = "quantity must be at least 1") int quantity
) {
}
