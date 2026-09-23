package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupplierConfirmationRequest(
        @NotBlank @Pattern(regexp = "CONFIRMED|REJECTED") String status,
        @Size(max = 100) String supplierReference,
        @Size(max = 1000) String note) {
    @jakarta.validation.constraints.AssertTrue(message = "A rejection requires a reason")
    public boolean isRejectionReasonPresent() {
        return !"REJECTED".equals(status) || note != null && !note.isBlank();
    }
}
