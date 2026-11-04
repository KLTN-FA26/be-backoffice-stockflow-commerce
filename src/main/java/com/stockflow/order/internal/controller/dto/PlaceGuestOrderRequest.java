package com.stockflow.order.internal.controller.dto;

import com.stockflow.common.validation.VietnamPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PlaceGuestOrderRequest(
        @NotNull UUID requestId,
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull @Valid Address shippingAddress,
        boolean billingSameAsShipping,
        @Valid Address billingAddress,
        @NotEmpty List<@Valid Line> lines
) {
    public record Address(
            @NotBlank @Size(max = 200) String recipientName,
            @NotBlank @VietnamPhone String phone,
            @NotBlank @Size(max = 255) String line1,
            @Size(max = 255) String line2,
            @NotBlank @Size(max = 20) String wardCode,
            @NotBlank @Size(max = 120) String wardName,
            @NotBlank @Size(max = 20) String provinceCode,
            @NotBlank @Size(max = 120) String provinceName,
            @NotBlank @Pattern(regexp = "VN") String countryCode,
            @Size(max = 20) String postalCode
    ) {
    }

    public record Line(
            @NotBlank String sku,
            @Min(1) int quantity,
            @NotNull @DecimalMin(value = "0.0", inclusive = false, message = "unitPrice must be greater than zero") BigDecimal unitPrice
    ) {
    }
}
