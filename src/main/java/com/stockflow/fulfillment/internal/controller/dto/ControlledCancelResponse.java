package com.stockflow.fulfillment.internal.controller.dto;

/**
 * One vehicle called off under control (SCRUM-474 UNLOAD-DESIGN point 2).
 *
 * @param cancelled   the load, now CANCELLED, with {@code pendingUnloads}: the parcels locked until each is scanned off
 * @param replacement the PLANNED load that took its place, same plan vehicle and manifest, to book with another truck;
 *                    null when the vehicle was already called off and nothing replaced it
 */
public record ControlledCancelResponse(LoadResponse cancelled, LoadResponse replacement) {
}
