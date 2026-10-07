package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.NotBlank;

public record RecoverPurchaseOrderDeliveryRequest(@NotBlank @Size(max = 1000) String reason,
        boolean reconciled, boolean acknowledgePastDue) {}
