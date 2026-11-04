package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token from the invitation link and the password the contact chose. Sent in a POST body, never
 * a query string, so neither ends up in an access log. The password policy itself is identity's.
 */
public record ActivatePortalAccountRequest(
        @NotBlank @Size(max = 128) String token,
        @NotBlank @Size(max = 72) String password
) {
    @Override
    public String toString() {
        return "ActivatePortalAccountRequest[token=REDACTED, password=REDACTED]";
    }
}
