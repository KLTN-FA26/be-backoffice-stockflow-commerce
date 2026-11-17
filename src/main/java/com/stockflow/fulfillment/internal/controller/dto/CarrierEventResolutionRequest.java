package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * How an error-list event was dealt with (kltn-docs 09 §4.5 step 8: recorded or corrected by hand, a waybill number
 * typed, settled with the carrier).
 *
 * @param note required, at most 1000 characters
 */
public record CarrierEventResolutionRequest(@NotBlank @Size(max = 1000) String note) {
}
