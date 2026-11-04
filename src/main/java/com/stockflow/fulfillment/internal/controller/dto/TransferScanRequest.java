package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One scan at a transfer's packing (SCRUM-287 PR T; 08 step 2). As {@link PackScanRequest}, with
 * {@code transferLineId} only when the code is on two lines of the transfer (two lots of one SKU); no serial numbers -
 * a transfer does not track them.
 */
public record TransferScanRequest(@NotNull UUID scanId,
                                  @NotBlank @Size(max = 64) String scannedCode,
                                  @Size(max = 64) String lotNumber,
                                  @NotNull @Positive @Max(1_000_000) Integer quantity,
                                  UUID transferLineId,
                                  @Size(max = 500) String kitOverrideReason) {
}
