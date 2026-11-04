package com.stockflow.fulfillment.internal.controller.dto;

import java.util.UUID;

/** The carrier a shipment waiting for dispatch goes with (09 §4.1 step 2); null clears it. */
public record AssignCarrierRequest(UUID carrierId) {
}
