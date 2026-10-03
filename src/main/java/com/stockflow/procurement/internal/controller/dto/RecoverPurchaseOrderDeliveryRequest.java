package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoverPurchaseOrderDeliveryRequest(@NotBlank @Size(max = 1000) String reason,
        boolean reconciled, boolean acknowledgePastDue) {}
