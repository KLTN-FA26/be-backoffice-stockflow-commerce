package com.stockflow.fulfillment.internal.domain;

/**
 * How a shipment travels (kltn-docs 09 §1): parcels through a carrier, or a full truckload planned in 3D (09 §4.2).
 * The order's own choice arrives with G3-4; until then every shipment is a PARCEL.
 */
public enum DeliveryMode { PARCEL, FULL_TRUCKLOAD }
