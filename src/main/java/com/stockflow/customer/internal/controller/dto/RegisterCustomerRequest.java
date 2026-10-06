package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 320) String email,
        @VietnamPhone String phone,
        @NotBlank @Size(min = 10, max = 72)
        @Pattern(regexp = "(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+",
                message = "password must contain upper, lower and digit characters")
        String password
) {
}
