package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The purchase order a receipt without one belongs to (SCRUM-478, owner decision NOPO-ATTACH): a reference only. */
public record ReferencePurchaseOrderRequest(@NotNull UUID purchaseOrderId) {
}
