package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A customer's legal profile, whole (4B-4); {@code version} is the one read, absent for the first. */
public record SaveLegalProfileRequest(
        @NotBlank @Size(max = 500) String registeredAddress,
        @NotBlank @Size(max = 200) String representativeName,
        @NotBlank @Size(max = 100) String representativeTitle,
        Long version
) {
}
