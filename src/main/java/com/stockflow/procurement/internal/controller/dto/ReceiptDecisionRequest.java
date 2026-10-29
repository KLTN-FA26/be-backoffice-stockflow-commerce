package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The warehouse manager's decision on a receipt waiting for approval (SCRUM-477, BR-RCP-001): approve its whole count,
 * or reject its excess. Either way the reason is kept on the receipt with the person who decided.
 */
public record ReceiptDecisionRequest(@NotBlank @Size(max = 500) String reason) {
}
