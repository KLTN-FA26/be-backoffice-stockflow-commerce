package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The delivered shipment to draw an e-invoice up for; with no {@code shipmentId}, the invoice of the rest of the
 * order's shipping fee, once its undelivered rest was closed.
 */
public record DrawUpEInvoiceRequest(@NotNull UUID orderId, UUID shipmentId) {
}
