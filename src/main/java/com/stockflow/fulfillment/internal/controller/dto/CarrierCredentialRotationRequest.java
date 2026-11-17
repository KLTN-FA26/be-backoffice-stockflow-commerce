package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A step in replacing the credential an API_BOOKING carrier's webhooks carry (WF-05 fix plan item 6).
 *
 * @param step    START (a second credential starts to hold), PROMOTE (it becomes the only one) or DISCARD (it is
 *                dropped)
 * @param value   START: the new credential, 16-512 characters; write-only, never shown again
 * @param version the carrier's version as read
 */
public record CarrierCredentialRotationRequest(@NotBlank String step,
                                               @Size(max = 512) String value,
                                               @NotNull Long version) {

    /** Never prints the credential: a request logged or shown in an error must not carry one. */
    @Override
    public String toString() {
        return "CarrierCredentialRotationRequest[step=" + step + ", value=" + (value == null ? null : "***")
                + ", version=" + version + "]";
    }
}
