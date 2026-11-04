package com.stockflow.order.api;

/**
 * How an order travels (kltn-docs 09 §1, §3 "Hình thức giao"; 17 §4.2 step 4): parcels through a carrier by default, or
 * a full truckload planned in 3D (09 §4.2). LP-1 (lead's decision 2026-10-22): the Order Coordinator chooses it when
 * releasing the order and may change it until packing starts - the first package opened - after which it is locked.
 * Packing copies it onto every shipment of the order.
 *
 * <p>The names are the shipment's (fulfillment {@code DeliveryMode}, {@code ck_shipment_delivery_mode}): one vocabulary
 * from the order to the dispatch load.</p>
 */
public enum DeliveryMode {
    /** Parcels handed to a carrier on a manifest (09 §4.1). The default. */
    PARCEL,
    /**
     * A whole truck or container hired for the order (09 §4.2). Never with a balance the carrier collects on delivery
     * (LP-2, {@code ck_shipment_ftl_no_cod}): Sales changes the balance policy first.
     */
    FULL_TRUCKLOAD
}
