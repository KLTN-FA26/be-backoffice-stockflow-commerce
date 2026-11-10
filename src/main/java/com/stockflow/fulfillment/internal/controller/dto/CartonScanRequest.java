package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One scan at the check station (kltn-docs 08 §4.2 step 3): the LPN of a carton picked for the task, in any case.
 *
 * @param lpn what the scanner read from the carton's LPN label
 */
public record CartonScanRequest(@NotBlank @Size(max = 30) String lpn) {
}
