package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Open (or get) the packing session of a transfer order at its source warehouse (SCRUM-287 PR T, 10 step 5). */
public record TransferPackRequest(@NotNull UUID transferOrderId) {
}
