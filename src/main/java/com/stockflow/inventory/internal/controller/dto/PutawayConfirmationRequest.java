package com.stockflow.inventory.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the clerk scanned: the bin, and the carton's LPN (kltn-docs 05 BR-08: a task is one carton, put away whole by
 * scanning it). No quantity: there are no loose putaway tasks any more (team plan 4C-2).
 */
public record PutawayConfirmationRequest(
        @NotBlank @Size(max = 64) String locationCode,
        @NotBlank @Size(max = 30) String lpn) {
}
