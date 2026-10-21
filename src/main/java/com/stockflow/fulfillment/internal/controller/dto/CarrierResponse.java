package com.stockflow.fulfillment.internal.controller.dto;

import java.util.UUID;

/** A carrier of the catalogue (G4-2a). */
public record CarrierResponse(UUID carrierId, String code, String name, String contactName, String contactPhone,
                              String status, long version) {
}
