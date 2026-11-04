package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A carrier of the catalogue (kltn-docs 09 §3: name and contact).
 *
 * @param code    2-32 upper-case letters, digits, '-' or '_' (lower case is upper-cased)
 * @param status  ACTIVE (default) or INACTIVE: an inactive carrier takes no new load
 * @param version required on PUT: the version read
 */
public record CarrierRequest(@NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
                             @NotBlank @Size(max = 120) String name,
                             @Size(max = 120) String contactName,
                             @Size(max = 32) String contactPhone,
                             @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String status,
                             Long version) {
}
