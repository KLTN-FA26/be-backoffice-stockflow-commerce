package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Both sides sign the manifest (kltn-docs 09 §4.1 step 3). The time is the server's.
 *
 * @param version               the load's version on the screen the driver signed (409 OPTIMISTIC_LOCK if it changed)
 * @param carrierRepresentative who signed for the carrier: the driver's name
 * @param vehiclePlate          required unless given when the load was opened
 * @param signatureRef          the reference of the signed paper manifest, or of its scan
 * @param signed                must be true: 400 HANDOVER_NOT_SIGNED otherwise
 */
public record HandoverRequest(@NotNull Long version,
                              @NotBlank @Size(max = 120) String carrierRepresentative,
                              @Size(max = 20) String vehiclePlate,
                              @Size(max = 32) String driverPhone,
                              @Size(max = 255) String signatureRef,
                              @NotNull Boolean signed,
                              @Size(max = 500) String note) {
}
