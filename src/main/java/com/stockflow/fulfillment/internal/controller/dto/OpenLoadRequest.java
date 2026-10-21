package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The carrier's driver arrived: open a parcel load (kltn-docs 09 §4.1 step 3).
 *
 * @param requestId   made by the client, new per load: the same request again answers with the load it opened
 * @param shipmentIds the shipments to put on the manifest; empty: those of this warehouse waiting for dispatch already
 *                    assigned to this carrier and on no other load
 */
public record OpenLoadRequest(@NotNull UUID requestId,
                              @NotNull UUID warehouseId,
                              @NotNull UUID carrierId,
                              @Size(max = 200) List<@NotNull UUID> shipmentIds,
                              @Size(max = 20) String vehiclePlate,
                              @Size(max = 120) String driverName,
                              @Size(max = 32) String driverPhone) {
}
