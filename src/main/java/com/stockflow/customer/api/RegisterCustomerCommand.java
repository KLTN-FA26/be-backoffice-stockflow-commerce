package com.stockflow.customer.api;

/** Public account registration. Identity owns credentials; customer owns the profile. */
public record RegisterCustomerCommand(
        String fullName,
        String email,
        String phone,
        String password
) {
}
