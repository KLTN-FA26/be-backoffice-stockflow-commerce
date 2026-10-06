package com.stockflow.customer.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SaveAddressRequest(
        @NotBlank @Pattern(regexp = "SHIPPING|BILLING") String type,
        @NotBlank @Size(max = 200) String recipientName,
        @NotBlank @VietnamPhone String phone,
        @NotBlank @Size(max = 255) String line1,
        @Size(max = 255) String line2,
        @NotBlank @Size(max = 20) String wardCode,
        @NotBlank @Size(max = 120) String wardName,
        @NotBlank @Size(max = 20) String provinceCode,
        @NotBlank @Size(max = 120) String provinceName,
        @NotBlank @Pattern(regexp = "VN", message = "only VN addresses are supported") String countryCode,
        @Size(max = 20) String postalCode,
        boolean defaultAddress,
        @PositiveOrZero Long version
) {
}
