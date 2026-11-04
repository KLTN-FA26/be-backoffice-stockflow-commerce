package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One scan at the packing table (kltn-docs 08 step 2).
 *
 * @param scanId       made by the scanner, new for every scan: the same scan sent again changes nothing
 * @param scannedCode  the item's SKU or barcode
 * @param lotNumber    required when the goods picked for the line have a lot
 * @param serialNumber required, one unit per scan, when the goods have serial numbers
 * @param orderLineId  only when the code is on two lines of the order (a plain and a printed cup of one SKU)
 * @param kitOverrideReason KIT-FORCE: required (422 KIT_OVERRIDE_REASON_REQUIRED) to scan an item that ships as
 *                     several boxes into an ordinary package that has no reason yet; kept on the package
 */
public record PackScanRequest(@NotNull UUID scanId,
                              @NotBlank @Size(max = 64) String scannedCode,
                              @Size(max = 64) String lotNumber,
                              @Size(max = 100) String serialNumber,
                              @NotNull @Positive @Max(1_000_000) Integer quantity,
                              UUID orderLineId,
                              @Size(max = 500) String kitOverrideReason) {
}
