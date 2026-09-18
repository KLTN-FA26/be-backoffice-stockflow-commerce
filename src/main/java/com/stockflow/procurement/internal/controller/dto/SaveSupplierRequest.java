package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SaveSupplierRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String contactName,
        @Email @Size(max = 320) String email,
        @Pattern(regexp = "^$|^\\+?[0-9 .()-]{8,20}$") String phone,
        @Pattern(regexp = "^$|^[0-9A-Za-z-]{8,32}$") String taxCode,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @Min(0) @Max(365) int paymentTermDays,
        @Min(0) @Max(365) int leadTimeDays,
        @NotBlank @Pattern(regexp = "EMAIL|API") String communicationChannel,
        @Size(max = 500) String apiEndpoint) { }
