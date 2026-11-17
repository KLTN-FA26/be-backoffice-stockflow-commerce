package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The account a real e-invoice provider is called with. {@code secret} is the account's password: written here, sealed,
 * and never returned; left out, the one stored is kept.
 */
public record StoreEInvoiceProviderAccountRequest(
        @NotNull @Pattern(regexp = "SANDBOX|PRODUCTION") String environment,
        @NotBlank @Size(max = 200) String username,
        @Size(min = 1, max = 500) String secret,
        @NotNull Long version
) {

    @Override
    public String toString() {
        // A record prints its components, and a request is the kind of thing that ends up in a log line.
        return "StoreEInvoiceProviderAccountRequest[environment=" + environment + ", version=" + version + "]";
    }
}
