package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(
        @NotBlank @Size(max = 200) String fullName,
        @VietnamPhone String phone,
        @PositiveOrZero long version,
        /* Staff edit only; ignored on /me. Normalised and checked by the service. */
        @Size(max = 20) String taxCode
) {
}
