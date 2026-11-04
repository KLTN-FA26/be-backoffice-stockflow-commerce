package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Shipments to put on a load's manifest. */
public record LoadShipmentsRequest(@NotEmpty @Size(max = 200) List<@NotNull UUID> shipmentIds) {
}
