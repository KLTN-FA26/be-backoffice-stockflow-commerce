package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Cartons of one task named by LPN, for their shipping labels (kltn-docs 08 §4.3). The body is optional: without it, or
 * with no LPN, every carton concerned is meant - every CHECKED one to label, every labelled one not shipped to print.
 *
 * @param lpns the cartons, in any case; at most 500 at a time
 */
public record CartonSelectionRequest(@Size(max = 500) List<@NotBlank @Size(max = 30) String> lpns) {
}
