package com.stockflow.inventory.api;

/**
 * How a transfer order travels (SCRUM-287 PR T, lead's decision LP2-8; kltn-docs 10 §4 step 5, 09 BR-08).
 *
 * <ul>
 *   <li>{@link #FULL_TRUCKLOAD} - the default: the source warehouse packs it at the station, plans the trip with load
 *       planning (one transfer per plan) and the last vehicle's signature dispatches it.</li>
 *   <li>{@link #PARCEL} - it goes without a load plan: dispatched by hand as before (SCRUM-327).</li>
 * </ul>
 */
public enum TransferDeliveryMode {
    PARCEL,
    FULL_TRUCKLOAD
}
