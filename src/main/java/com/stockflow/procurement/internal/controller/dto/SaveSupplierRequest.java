package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@ValidProcurementFields
public record SaveSupplierRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String contactName,
        @Email @Size(max = 320) String email,
        @Pattern(regexp = "^$|^\\+?[0-9](?:[0-9 .()-]*[0-9])?$") @Size(max = 32) String phone,
        @Pattern(regexp = "^$|^(?=.*[0-9])[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$") String taxCode,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @NotNull(groups = Update.class) @Min(0) @Max(365) Integer paymentTermDays,
        @NotNull(groups = Update.class) @Min(0) @Max(365) Integer leadTimeDays,
        @NotBlank @Pattern(regexp = "EMAIL|API") String communicationChannel,
        @Size(max = 500) String apiEndpoint) {
    /** PUT must carry existing commercial values rather than accidentally applying create defaults. */
    public interface Update extends Default {}

    public boolean isDeliveryContactValid() {
        if ("EMAIL".equals(communicationChannel)) return email != null && !email.isBlank();
        if (!"API".equals(communicationChannel)) return true;
        try {
            var uri = java.net.URI.create(apiEndpoint == null ? "" : apiEndpoint);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && uri.getQuery() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean isPhoneDigitsValid() {
        if (phone == null || phone.isEmpty()) return true;
        long digits = phone.chars().filter(c -> c >= '0' && c <= '9').count();
        return digits >= 8 && digits <= 15;
    }
}
