package com.stockflow.fulfillment.internal.domain.loadplanning;

/** What a vehicle type is ({@code fulfillment.vehicle_type.kind}): only a container has container-specific rules. */
public enum VehicleKind {
    CONTAINER,
    TRUCK,
    PICKUP
}
