package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@ValidProcurementFields
public record SupplierConfirmationRequest(
        @NotBlank @Pattern(regexp = "CONFIRMED|REJECTED") String status,
        @Size(max = 100) String supplierReference,
        @Size(max = 1000) String note) {
    public boolean isRejectionReasonPresent() {
        return !"REJECTED".equals(status) || note != null && !note.isBlank();
    }
}
