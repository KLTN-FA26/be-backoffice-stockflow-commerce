package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The delivered shipment to draw an e-invoice up for. */
public record DrawUpEInvoiceRequest(@NotNull UUID orderId, @NotNull UUID shipmentId) {
}
